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
package org.commonjava.aprox.dotmaven.store.sub;

import static org.apache.commons.io.IOUtils.closeQuietly;
import static org.apache.commons.io.IOUtils.copy;
import static org.apache.commons.lang.StringUtils.isEmpty;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.inject.Named;

import net.sf.webdav.StoredObject;
import net.sf.webdav.exceptions.WebdavException;
import net.sf.webdav.spi.ITransaction;

import org.commonjava.aprox.data.ProxyDataException;
import org.commonjava.aprox.data.StoreDataManager;
import org.commonjava.aprox.dotmaven.DotMavenException;
import org.commonjava.aprox.dotmaven.data.StorageAdvice;
import org.commonjava.aprox.dotmaven.data.StorageAdvisor;
import org.commonjava.aprox.dotmaven.store.SubStore;
import org.commonjava.aprox.dotmaven.util.StoreURIMatcher;
import org.commonjava.aprox.filer.FileManager;
import org.commonjava.aprox.model.ArtifactStore;
import org.commonjava.aprox.model.StoreKey;
import org.commonjava.aprox.model.StoreType;
import org.commonjava.aprox.util.LocationUtils;
import org.commonjava.aprox.util.StringFormat;
import org.commonjava.maven.galley.model.Transfer;
import org.commonjava.maven.galley.model.TransferOperation;
import org.commonjava.maven.galley.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
@Named( "stores" )
public class ArtifactStoreSubStore
    implements SubStore
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private StoreDataManager aprox;

    @Inject
    private StorageAdvisor advisor;

    @Inject
    private FileManager fileManager;

    @Override
    public String[] getRootResourceNames()
    {
        return new String[] { "storage" };
    }

    @Override
    public boolean matchesUri( final String uri )
    {
        return new StoreURIMatcher( uri ).matches();
    }

    @Override
    public void createFolder( final ITransaction transaction, final String folderUri )
        throws WebdavException
    {
        final StoreURIMatcher matcher = new StoreURIMatcher( folderUri );
        if ( !matcher.hasStorePath() )
        {
            throw new WebdavException( "No store-level path specified: '" + folderUri
                + "'. This URI references either a list of stores, a root store directory, or something else equally read-only." );
        }

        final StorageAdvice advice = getStorageAdviceFor( matcher );

        final String path = matcher.getStorePath();
        final Transfer item = fileManager.getStorageReference( advice.getHostedStore(), path );
        try
        {
            item.mkdirs();
        }
        catch ( final IOException e )
        {
            logger.error( "Failed to create folder: {} in store: {}. Reason: {}", e, path, advice.getStore()
                                                                                                 .getKey(), e.getMessage() );
            throw new WebdavException( "Failed to create folder: " + folderUri );
        }
    }

    @Override
    public void createResource( final ITransaction transaction, final String resourceUri )
        throws WebdavException
    {
        final StoreURIMatcher matcher = new StoreURIMatcher( resourceUri );
        if ( !matcher.hasStorePath() )
        {
            throw new WebdavException( "No store-level path specified: '" + resourceUri
                + "'. This URI references either a list of stores, a root store directory, or something else equally read-only." );
        }

        final StorageAdvice advice = getStorageAdviceFor( matcher );

        final String path = matcher.getStorePath();
        final Transfer item = fileManager.getStorageReference( advice.getHostedStore(), path );
        try
        {
            item.createFile();
        }
        catch ( final IOException e )
        {
            logger.error( "Failed to create file: {} in store: {}. Reason: {}", e, path, advice.getStore()
                                                                                               .getKey(), e.getMessage() );
            throw new WebdavException( "Failed to create file: " + resourceUri );
        }
    }

    @Override
    public InputStream getResourceContent( final ITransaction transaction, final String resourceUri )
        throws WebdavException
    {
        final StoreURIMatcher matcher = new StoreURIMatcher( resourceUri );
        final Transfer item = getTransfer( matcher );
        if ( item == null )
        {
            throw new WebdavException( "Cannot read content: " + resourceUri );
        }

        final String path = item.getPath();
        final StoreKey key = LocationUtils.getKey( item );

        try
        {
            return item.openInputStream();
        }
        catch ( final IOException e )
        {
            logger.error( "{}", e, new StringFormat( "Failed to open InputStream for: {} in store: {}. Reason: {}", path, key, e.getMessage() ) );
            throw new WebdavException( "Failed to get content for: " + resourceUri );
        }
    }

    private Transfer getTransfer( final StoreURIMatcher matcher )
        throws WebdavException
    {
        final String resourceUri = matcher.getURI();

        if ( !matcher.hasStorePath() )
        {
            throw new WebdavException( "No store-level path specified: '" + resourceUri
                + "'. This URI references either a list of stores, a root store directory, or something else that cannot be read as a file." );
        }

        final String path = matcher.getStorePath();
        final StoreKey key = matcher.getStoreKey();

        Transfer item = null;
        try
        {
            if ( StoreType.group == key.getType() )
            {
                final List<ArtifactStore> stores = aprox.getOrderedStoresInGroup( key.getName() );
                for ( final ArtifactStore store : stores )
                {
                    //                    logger.info( "Getting Transfer for: {} from: {}", path, store );
                    final Transfer si = fileManager.getStorageReference( store, path );
                    if ( si.exists() )
                    {
                        //                        logger.info( "Using Transfer: {} for path: {}", si, path );
                        item = si;
                        break;
                    }
                }
            }
            else
            {
                final ArtifactStore store = aprox.getArtifactStore( key );
                //                logger.info( "Getting Transfer for: {} from: {}", path, store );
                final Transfer si = fileManager.getStorageReference( store, path );
                if ( si.exists() )
                {
                    //                    logger.info( "Using Transfer: {} for path: {}", si, path );
                    item = si;
                }
            }
        }
        catch ( final ProxyDataException e )
        {
            logger.error( "{}", e, new StringFormat( "Failed to lookup ArtifactStore(s) for key: {}. Reason: {}", key, e.getMessage() ) );
            throw new WebdavException( "Failed to get content for: " + resourceUri );
        }

        return item;
    }

    @Override
    public long setResourceContent( final ITransaction transaction, final String resourceUri, final InputStream content, final String contentType,
                                    final String characterEncoding )
        throws WebdavException
    {
        final StoreURIMatcher matcher = new StoreURIMatcher( resourceUri );
        if ( !matcher.hasStorePath() )
        {
            throw new WebdavException( "No store-level path specified: '" + resourceUri
                + "'. This URI references either a list of stores, a root store directory, or something else equally read-only." );
        }

        final StorageAdvice advice = getStorageAdviceFor( matcher );

        final String path = matcher.getStorePath();
        final Transfer item = fileManager.getStorageReference( advice.getHostedStore(), path );
        Writer writer = null;
        try
        {
            if ( characterEncoding != null )
            {
                writer = new OutputStreamWriter( item.openOutputStream( TransferOperation.UPLOAD ), characterEncoding );
            }
            else
            {
                writer = new OutputStreamWriter( item.openOutputStream( TransferOperation.UPLOAD ) );
            }

            copy( content, writer );

            return item.getDetachedFile()
                       .length();
        }
        catch ( final IOException e )
        {
            logger.error( "Failed to write file: {} in store: {}. Reason: {}", e, path, advice.getStore()
                                                                                              .getKey(), e.getMessage() );
            throw new WebdavException( "Failed to write file: " + resourceUri );
        }
        finally
        {
            closeQuietly( writer );
        }
    }

    @Override
    public String[] getChildrenNames( final ITransaction transaction, final String folderUri )
        throws WebdavException
    {
        String[] names;
        final StoreURIMatcher matcher = new StoreURIMatcher( folderUri );
        if ( matcher.hasStorePath() || matcher.hasStoreName() )
        {
            String path = matcher.getStorePath();
            if ( isEmpty( path ) )
            {
                path = PathUtils.ROOT;
            }

            final StoreKey key = matcher.getStoreKey();
            try
            {
                if ( StoreType.group == key.getType() )
                {
                    final List<ArtifactStore> stores = aprox.getOrderedStoresInGroup( key.getName() );
                    final Set<String> noms = new TreeSet<String>();
                    for ( final ArtifactStore store : stores )
                    {
                        final Transfer item = fileManager.getStorageReference( store, path );
                        if ( !item.exists() )
                        {
                            continue;
                        }

                        if ( !item.isDirectory() )
                        {
                            logger.error( "Transfer: {} in {} is not a directory.", path, store.getKey() );
                            continue;
                        }

                        noms.addAll( Arrays.asList( item.list() ) );
                    }

                    names = noms.toArray( new String[] {} );
                }
                else
                {
                    final ArtifactStore store = aprox.getArtifactStore( key );
                    final Transfer item = fileManager.getStorageReference( store, path );
                    if ( !item.exists() || !item.isDirectory() )
                    {
                        logger.error( "Transfer: {} in {} is not a directory.", path, store.getKey() );
                        names = new String[] {};
                    }
                    else
                    {
                        names = item.list();
                    }
                }
            }
            catch ( final ProxyDataException e )
            {
                logger.error( "{}", e, new StringFormat( "Failed to lookup ArtifactStore(s) for key: {}. Reason: {}", key, e.getMessage() ) );
                throw new WebdavException( "Failed to get listing for: " + folderUri );
            }
        }
        else if ( matcher.hasStoreType() )
        {
            final StoreType type = matcher.getStoreType();
            List<? extends ArtifactStore> stores;
            try
            {
                stores = aprox.getAllArtifactStores( type );
            }
            catch ( final ProxyDataException e )
            {
                logger.error( "{}", e, new StringFormat( "Failed to lookup ArtifactStores of type: {}. Reason: {}", type, e.getMessage() ) );
                throw new WebdavException( "Failed to get listing for: " + folderUri );
            }

            final Set<String> noms = new TreeSet<String>();
            for ( final ArtifactStore store : stores )
            {
                noms.add( store.getName() );
            }

            names = noms.toArray( new String[] {} );
        }
        else
        {
            names =
                new String[] { StoreType.hosted.singularEndpointName(), StoreType.group.singularEndpointName(),
                    StoreType.remote.singularEndpointName() };
        }

        return names;
    }

    @Override
    public long getResourceLength( final ITransaction transaction, final String path )
        throws WebdavException
    {
        final StoreURIMatcher matcher = new StoreURIMatcher( path );
        if ( matcher.hasStorePath() )
        {
            final Transfer item = getTransfer( matcher );
            if ( item != null )
            {
                return item.getDetachedFile()
                           .length();
            }

        }

        return 0;
    }

    @Override
    public void removeObject( final ITransaction transaction, final String uri )
        throws WebdavException
    {
        final StoreURIMatcher matcher = new StoreURIMatcher( uri );
        if ( !matcher.hasStorePath() )
        {
            throw new WebdavException( "No store-level path specified: '" + uri
                + "'. This URI references either a list of stores, a root store directory, or something else equally read-only." );
        }

        final StorageAdvice advice = getStorageAdviceFor( matcher );

        final String path = matcher.getStorePath();
        final Transfer item = fileManager.getStorageReference( advice.getHostedStore(), path );
        try
        {
            if ( item.exists() )
            {
                item.delete();
            }
        }
        catch ( final IOException e )
        {
            logger.error( "Failed to delete file: {} in store: {}. Reason: {}", e, path, advice.getStore()
                                                                                               .getKey(), e.getMessage() );
            throw new WebdavException( "Failed to delete file: " + uri );
        }
    }

    @Override
    public StoredObject getStoredObject( final ITransaction transaction, final String uri )
        throws WebdavException
    {
        final StoredObject so = new StoredObject();

        final StoreURIMatcher matcher = new StoreURIMatcher( uri );
        if ( matcher.hasStorePath() )
        {
            final Transfer item = getTransfer( matcher );

            if ( item == null )
            {
                return null;
            }

            final File f = item.getDetachedFile();
            so.setCreationDate( new Date( f.lastModified() ) );
            so.setLastModified( new Date( f.lastModified() ) );
            so.setFolder( f.isDirectory() );
            so.setResourceLength( f.length() );
        }
        else
        {
            final Date d = new Date();
            so.setCreationDate( d );
            so.setLastModified( d );
            so.setFolder( true );
        }

        return so;
    }

    private StorageAdvice getStorageAdviceFor( final StoreURIMatcher matcher )
        throws WebdavException
    {
        final String uri = matcher.getURI();
        final StoreKey key = matcher.getStoreKey();
        ArtifactStore store;
        try
        {
            store = aprox.getArtifactStore( key );
        }
        catch ( final ProxyDataException e )
        {
            logger.error( "{}", e, new StringFormat( "Failed to retrieve artifact store: {} for URI: {}\nReason: {}", key, uri, e.getMessage() ) );
            throw new WebdavException( "Cannot create: " + uri );
        }

        StorageAdvice advice;
        try
        {
            advice = advisor.getStorageAdvice( store );
        }
        catch ( final DotMavenException e )
        {
            logger.error( "{}", e, new StringFormat( "Failed to retrieve storage advice for: {} (URI: {})\nReason: {}", key, uri, e.getMessage() ) );
            throw new WebdavException( "Cannot create: " + uri );
        }

        if ( !advice.isDeployable() )
        {
            throw new WebdavException( "Read-only area. Cannot create: " + uri );
        }

        return advice;
    }

}
