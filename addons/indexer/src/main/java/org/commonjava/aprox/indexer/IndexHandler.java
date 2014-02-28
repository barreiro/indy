/*******************************************************************************
 * Copyright (C) 2014 John Casey.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package org.commonjava.aprox.indexer;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

import org.apache.maven.index.ArtifactScanningListener;
import org.apache.maven.index.DefaultScannerListener;
import org.apache.maven.index.Indexer;
import org.apache.maven.index.IndexerEngine;
import org.apache.maven.index.Scanner;
import org.apache.maven.index.ScanningRequest;
import org.apache.maven.index.ScanningResult;
import org.apache.maven.index.context.ExistingLuceneIndexMismatchException;
import org.apache.maven.index.context.IndexCreator;
import org.apache.maven.index.context.IndexingContext;
import org.apache.maven.index.updater.IndexUpdateRequest;
import org.apache.maven.index.updater.IndexUpdateResult;
import org.apache.maven.index.updater.IndexUpdater;
import org.apache.maven.index.updater.ResourceFetcher;
import org.commonjava.aprox.change.event.ArtifactStoreUpdateEvent;
import org.commonjava.aprox.change.event.ProxyManagerDeleteEvent;
import org.commonjava.aprox.data.ProxyDataException;
import org.commonjava.aprox.data.StoreDataManager;
import org.commonjava.aprox.filer.FileManager;
import org.commonjava.aprox.indexer.inject.IndexCreatorSet;
import org.commonjava.aprox.model.ArtifactStore;
import org.commonjava.aprox.model.Group;
import org.commonjava.aprox.model.HostedRepository;
import org.commonjava.aprox.model.StoreKey;
import org.commonjava.aprox.model.StoreType;
import org.commonjava.aprox.util.LocationUtils;
import org.commonjava.aprox.util.StringFormat;
import org.commonjava.cdi.util.weft.ExecutorConfig;
import org.commonjava.maven.atlas.ident.util.JoinString;
import org.commonjava.maven.galley.event.FileStorageEvent;
import org.commonjava.maven.galley.model.Transfer;
import org.commonjava.shelflife.ExpirationManager;
import org.commonjava.shelflife.ExpirationManagerException;
import org.commonjava.shelflife.event.ExpirationEvent;
import org.commonjava.shelflife.model.Expiration;
import org.commonjava.shelflife.model.ExpirationKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class IndexHandler
{

    public static final long GROUP_INDEX_TIMEOUT = TimeUnit.MILLISECONDS.convert( 24, TimeUnit.HOURS );

    public static final long DEPLOY_POINT_INDEX_TIMEOUT = TimeUnit.MILLISECONDS.convert( 10, TimeUnit.MINUTES );

    public static final String INDEX_KEY_PREFIX = "aprox-index";

    private static final String INDEX_DIR = "/.index";

    private static final String INDEX_PROPERTIES = ".index/nexus-maven-repository-index-updater.properties";

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private Indexer indexer;

    @Inject
    private IndexerEngine indexerEngine;

    @Inject
    private Scanner scanner;

    @Inject
    private IndexUpdater indexUpdater;

    @Inject
    private IndexCreatorSet indexCreators;

    @Inject
    private ExpirationManager expirationManager;

    @Inject
    private StoreDataManager storeDataManager;

    @Inject
    private FileManager fileManager;

    @Inject
    @ExecutorConfig( daemon = true, priority = 7, threads = 1, named = "aprox-indexer" )
    private Executor executor;

    public IndexHandler()
    {
    }

    public IndexHandler( final ExpirationManager expirationManager, final StoreDataManager storeDataManager, final FileManager fileManager )
        throws AproxIndexerException
    {
        this.expirationManager = expirationManager;
        this.storeDataManager = storeDataManager;
        this.fileManager = fileManager;
    }

    public void onDelete( @Observes final ProxyManagerDeleteEvent event )
    {
        executor.execute( new DeletionRunnable( event ) );
    }

    public void onStorage( @Observes final FileStorageEvent event )
    {
        final Transfer item = event.getTransfer();
        final StoreKey key = LocationUtils.getKey( item );
        final String path = item.getPath();
        if ( !isIndexable( path ) )
        {
            return;
        }

        if ( key.getType() == StoreType.hosted )
        {
            HostedRepository store = null;
            try
            {
                store = storeDataManager.getHostedRepository( key.getName() );
            }
            catch ( final ProxyDataException e )
            {
                logger.error( "{}", e, new StringFormat( "Failed to retrieve deploy-point for index update: {}. Reason: {}", key, e.getMessage() ) );
            }

            if ( store != null )
            {
                final Expiration exp = expirationForDeployPoint( key.getName() );
                try
                {
                    if ( !expirationManager.contains( exp ) )
                    {
                        expirationManager.schedule( exp );
                    }
                }
                catch ( final ExpirationManagerException e )
                {
                    logger.error( "{}", e, new StringFormat( "Failed to schedule index update for deploy-point: {}. Reason: {}", key, e.getMessage() ) );
                }
            }
        }
    }

    public void onExpire( @Observes final ExpirationEvent event )
    {
        final Expiration expiration = event.getExpiration();
        final String[] parts = expiration.getKey()
                                         .getParts();
        if ( !INDEX_KEY_PREFIX.equals( parts[0] ) )
        {
            return;
        }

        executor.execute( new IndexExpirationRunnable( expiration ) );
    }

    public void onAdd( @Observes final ArtifactStoreUpdateEvent event )
    {
        executor.execute( new AdditionRunnable( event ) );
    }

    private boolean isIndexable( final String path )
    {
        if ( path.endsWith( ".sha1" ) || path.endsWith( ".md5" ) || path.endsWith( "maven-metadata.xml" ) || path.endsWith( "archetype-catalog.xml" ) )
        {
            return false;
        }

        return true;
    }

    private synchronized void scanIndex( final ArtifactStore store )
    {
        final IndexingContext context = getIndexingContext( store, indexCreators.getCreators() );

        if ( context == null )
        {
            return;
        }

        scanIndex( store, context );
    }

    private synchronized void scanIndex( final ArtifactStore store, final IndexingContext context )
    {
        try
        {
            final ArtifactScanningListener listener = new DefaultScannerListener( context, indexerEngine, false, null );
            final ScanningRequest request = new ScanningRequest( context, listener );
            final ScanningResult result = scanner.scan( request );

            final List<Exception> exceptions = result.getExceptions();
            if ( exceptions != null && !exceptions.isEmpty() )
            {
                logger.error( "{}. While scanning: {}, encountered errors:\n\n  {}", store.getKey(), new JoinString( "\n\n  ", exceptions ) );
            }
        }
        finally
        {
            try
            {
                context.close( false );
            }
            catch ( final IOException e )
            {
                logger.error( "{}", e, new StringFormat( "Failed to close index for deploy point: {}. Reason: {}", store.getKey(), e.getMessage() ) );
            }
        }
    }

    private synchronized void updateGroupsFor( final StoreKey storeKey, final Set<ArtifactStore> updated, final boolean updateRepositoryIndexes )
    {
        try
        {
            final Set<Group> groups = storeDataManager.getGroupsContaining( storeKey );
            if ( groups != null )
            {
                for ( final Group group : groups )
                {
                    if ( updated.contains( group ) )
                    {
                        continue;
                    }

                    updateMergedIndex( group, updated, updateRepositoryIndexes );
                }
            }
        }
        catch ( final ProxyDataException e )
        {
            logger.error( "{}", e, new StringFormat( "Failed to retrieve groups that contain: {}. Reason: {}", storeKey, e.getMessage() ) );
        }
    }

    private synchronized void updateMergedIndex( final Group group, final Set<ArtifactStore> updated, final boolean updateRepositoryIndexes )
    {
        final IndexingContext groupContext = getIndexingContext( group, indexCreators.getCreators() );
        if ( groupContext == null )
        {
            return;
        }

        final Map<ArtifactStore, IndexingContext> contexts = getContextsFor( group, indexCreators.getCreators() );
        try
        {
            for ( final Map.Entry<ArtifactStore, IndexingContext> entry : contexts.entrySet() )
            {
                final ArtifactStore store = entry.getKey();
                if ( updated.contains( store ) )
                {
                    continue;
                }

                final StoreKey key = store.getKey();
                final IndexingContext context = entry.getValue();

                final Transfer item = fileManager.getStorageReference( store, INDEX_PROPERTIES );
                if ( !item.exists() )
                {
                    if ( updateRepositoryIndexes || key.getType() == StoreType.hosted )
                    {
                        scanIndex( store, context );
                    }
                }
                else if ( updateRepositoryIndexes && key.getType() == StoreType.remote )
                {
                    doIndexUpdate( context, key );
                }

                updated.add( store );

                if ( context == null )
                {
                    // TODO: Stand off for a bit?
                    return;
                }

                try
                {
                    if ( context.getIndexDirectoryFile()
                                .exists() )
                    {
                        groupContext.merge( context.getIndexDirectory() );
                    }
                }
                catch ( final IOException e )
                {
                    logger.error( "Failed to merge index from: {} into group index: {}", key, group.getKey() );
                }
            }

            try
            {
                groupContext.commit();
            }
            catch ( final IOException e )
            {
                logger.error( "{}", e, new StringFormat( "Failed to commit index updates for group: {}. Reason: {}", group.getKey(), e.getMessage() ) );
            }

            updated.add( group );

            try
            {
                final Expiration exp = expirationForGroup( group.getName() );

                expirationManager.schedule( exp );

                logger.info( "Next index update in group: {} scheduled for: {}", group.getName(), new Date( exp.getExpires() ) );
            }
            catch ( final ExpirationManagerException e )
            {
                logger.error( "{}", e,
                              new StringFormat( "Failed to schedule indexer trigger for group: {}. Reason: {}", group.getName(), e.getMessage() ) );
            }
        }
        finally
        {
            if ( groupContext != null )
            {
                try
                {
                    groupContext.close( false );
                }
                catch ( final IOException e )
                {
                    logger.error( "{}", e, new StringFormat( "Failed to close indexing context: {}", e.getMessage() ) );
                }
            }

            if ( contexts != null )
            {
                for ( final IndexingContext ctx : contexts.values() )
                {
                    try
                    {
                        ctx.close( false );
                    }
                    catch ( final IOException e )
                    {
                        logger.error( "{}", e, new StringFormat( "Failed to close indexing context: {}", e.getMessage() ) );
                    }
                }
            }
        }
    }

    private IndexUpdateResult doIndexUpdate( final IndexingContext mergedContext, final StoreKey key )
    {
        final ResourceFetcher resourceFetcher = new AproxResourceFetcher( storeDataManager, fileManager );

        final Date centralContextCurrentTimestamp = mergedContext.getTimestamp();
        final IndexUpdateRequest updateRequest = new IndexUpdateRequest( mergedContext, resourceFetcher );
        IndexUpdateResult updateResult = null;
        try
        {
            updateResult = indexUpdater.fetchAndUpdateIndex( updateRequest );
        }
        catch ( final IOException e )
        {
            logger.error( "Failed to update index for: {}. Reason: {}", key, e.getMessage() );
        }

        if ( updateResult == null )
        {
            return null;
        }

        if ( updateResult.isFullUpdate() )
        {
            logger.info( "FULL index update completed for: {}", key );
        }
        else if ( updateResult.getTimestamp() != null && updateResult.getTimestamp()
                                                                     .equals( centralContextCurrentTimestamp ) )
        {
            logger.info( "NO index update for: {}. Index is up-to-date.", key );
        }
        else
        {
            logger.info( "INCREMENTAL index update completed for: {} to cover period: {} - {}", key, centralContextCurrentTimestamp,
                         updateResult.getTimestamp() );
        }

        return updateResult;
    }

    private Map<ArtifactStore, IndexingContext> getContextsFor( final Group group, final List<IndexCreator> indexers )
    {
        Map<ArtifactStore, IndexingContext> contexts = null;
        try
        {
            final List<ArtifactStore> stores = storeDataManager.getOrderedConcreteStoresInGroup( group.getName() );
            if ( stores != null && !stores.isEmpty() )
            {
                contexts = new LinkedHashMap<ArtifactStore, IndexingContext>( stores.size() );
                for ( final ArtifactStore store : stores )
                {
                    final IndexingContext ctx = getIndexingContext( store, indexers );
                    if ( ctx != null )
                    {
                        contexts.put( store, ctx );
                    }
                }
            }
        }
        catch ( final ProxyDataException e )
        {
            logger.error( "{}", e,
                          new StringFormat( "Failed to retrieve ordered concrete stores in group: {}. Reason: {}", group.getName(), e.getMessage() ) );
        }

        return contexts;
    }

    private IndexingContext getIndexingContext( final ArtifactStore store, final List<IndexCreator> indexers )
    {
        final File indexDir = fileManager.getStorageReference( store, INDEX_DIR )
                                         .getDetachedFile();
        indexDir.mkdirs();

        final File rootDir = fileManager.getStorageReference( store, FileManager.ROOT_PATH )
                                        .getDetachedFile();

        final String id = store.getKey()
                               .toString();

        try
        {
            /* TODO:
            15:19:27,359 ERROR [org.commonjava.aprox.indexer.IndexHandler] (aprox-indexer-0) 
            Failed to create indexing context for: repository:central. 
                Reason: Cannot forcefully unlock a NativeFSLock which is held by 
                another indexer component: /var/lib/aprox/storage/repository-central/.index/write.lock: 
            org.apache.lucene.store.LockReleaseFailedException: 
                Cannot forcefully unlock a NativeFSLock which is held by another 
                indexer component: /var/lib/aprox/storage/repository-central/.index/write.lock
            at org.apache.lucene.store.NativeFSLock.release(NativeFSLockFactory.java:295) [lucene-core-3.6.1.jar:3.6.1 1362471 - thetaphi - 2012-07-17 12:40:12]
            at org.apache.lucene.index.IndexWriter.unlock(IndexWriter.java:4624) [lucene-core-3.6.1.jar:3.6.1 1362471 - thetaphi - 2012-07-17 12:40:12]
            at org.apache.maven.index.context.DefaultIndexingContext.prepareCleanIndex(DefaultIndexingContext.java:232) [indexer-core-5.1.0.jar:5.1.0]
            at org.apache.maven.index.context.DefaultIndexingContext.prepareIndex(DefaultIndexingContext.java:206) [indexer-core-5.1.0.jar:5.1.0]
            at org.apache.maven.index.context.DefaultIndexingContext.<init>(DefaultIndexingContext.java:147) [indexer-core-5.1.0.jar:5.1.0]
            at org.apache.maven.index.context.DefaultIndexingContext.<init>(DefaultIndexingContext.java:155) [indexer-core-5.1.0.jar:5.1.0]
            at org.apache.maven.index.DefaultIndexer.createIndexingContext(DefaultIndexer.java:76) [indexer-core-5.1.0.jar:5.1.0]
            at org.commonjava.aprox.indexer.IndexHandler.getIndexingContext(IndexHandler.java:442) [classes:]
            at org.commonjava.aprox.indexer.IndexHandler.getContextsFor(IndexHandler.java:411) [classes:]
            at org.commonjava.aprox.indexer.IndexHandler.updateMergedIndex(IndexHandler.java:264) [classes:]
            at org.commonjava.aprox.indexer.IndexHandler.access$300(IndexHandler.java:57) [classes:]
            at org.commonjava.aprox.indexer.IndexHandler$AdditionRunnable.run(IndexHandler.java:578) [classes:]
            at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1145) [rt.jar:1.7.0_25]
            at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:615) [rt.jar:1.7.0_25]
            at java.lang.Thread.run(Thread.java:724) [rt.jar:1.7.0_25]
             */
            final IndexingContext context = indexer.createIndexingContext( id, id, rootDir, indexDir, id, null, true, true, indexers );

            return context;
        }
        catch ( final ExistingLuceneIndexMismatchException e )
        {
            logger.error( "{}", e, new StringFormat( "Failed to create indexing context for: {}. Reason: {}", store.getKey(), e.getMessage() ) );
        }
        catch ( final IllegalArgumentException e )
        {
            logger.error( "{}", e, new StringFormat( "Failed to create indexing context for: {}. Reason: {}", store.getKey(), e.getMessage() ) );
        }
        catch ( final IOException e )
        {
            logger.error( "{}", e, new StringFormat( "Failed to create indexing context for: {}. Reason: {}", store.getKey(), e.getMessage() ) );
        }

        return null;
    }

    private Expiration expirationForGroup( final String name )
    {
        return new Expiration( new ExpirationKey( StoreType.group.name(), INDEX_KEY_PREFIX, name ), GROUP_INDEX_TIMEOUT,
                               new StoreKey( StoreType.group, name ) );
    }

    private Expiration expirationForDeployPoint( final String name )
    {
        return new Expiration( new ExpirationKey( StoreType.hosted.name(), INDEX_KEY_PREFIX, name ), DEPLOY_POINT_INDEX_TIMEOUT,
                               new StoreKey( StoreType.hosted, name ) );
    }

    public class IndexExpirationRunnable
        implements Runnable
    {
        private final Expiration expiration;

        public IndexExpirationRunnable( final Expiration expiration )
        {
            this.expiration = expiration;
        }

        @Override
        public void run()
        {
            final StoreKey key = StoreKey.fromString( (String) expiration.getData() );
            final StoreType type = key.getType();

            ArtifactStore store;
            try
            {
                store = storeDataManager.getArtifactStore( key );
            }
            catch ( final ProxyDataException e )
            {
                logger.error( "{}", e, new StringFormat( "Failed to update index for: {}. Reason: {}", key, e.getMessage() ) );
                return;
            }

            if ( type == StoreType.hosted )
            {
                scanIndex( store );
            }
            else if ( type == StoreType.group )
            {
                updateMergedIndex( (Group) store, new HashSet<ArtifactStore>(), false );
            }
        }
    }

    public class DeletionRunnable
        implements Runnable
    {
        private final ProxyManagerDeleteEvent event;

        public DeletionRunnable( final ProxyManagerDeleteEvent event )
        {
            this.event = event;
        }

        @Override
        public void run()
        {
            final StoreType type = event.getType();
            if ( type != StoreType.group )
            {
                final Set<ArtifactStore> updated = new HashSet<ArtifactStore>();
                for ( final String name : event )
                {
                    updateGroupsFor( new StoreKey( type, name ), updated, true );
                }
            }
            else
            {
                for ( final String name : event )
                {
                    try
                    {
                        expirationManager.cancel( expirationForGroup( name ) );
                    }
                    catch ( final ExpirationManagerException e )
                    {
                        logger.error( "{}", e, new StringFormat( "Failed to cancel indexer trigger for group: {}. Reason: {}", name, e.getMessage() ) );
                    }
                }
            }
        }
    }

    public class AdditionRunnable
        implements Runnable
    {
        private final ArtifactStoreUpdateEvent event;

        public AdditionRunnable( final ArtifactStoreUpdateEvent event )
        {
            this.event = event;
        }

        @Override
        public void run()
        {
            final Set<ArtifactStore> updated = new HashSet<ArtifactStore>();
            for ( final ArtifactStore store : event )
            {
                if ( store.getKey()
                          .getType() == StoreType.group )
                {
                    final Group group = (Group) store;
                    if ( updated.contains( group ) )
                    {
                        continue;
                    }

                    updateMergedIndex( group, updated, true );
                }
                else
                {
                    if ( store.getKey()
                              .getType() == StoreType.hosted )
                    {
                        scanIndex( store );
                    }

                    updateGroupsFor( store.getKey(), updated, true );
                }
            }
        }
    }

}
