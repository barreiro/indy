/*******************************************************************************
 * Copyright (c) 2014 Red Hat, Inc..
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 * 
 * Contributors:
 *     Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package org.commonjava.aprox.bind.jaxrs;

import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.FilterInfo;
import io.undertow.servlet.api.ServletInfo;
import io.undertow.servlet.util.ImmediateInstanceFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.servlet.DispatcherType;
import javax.servlet.Servlet;
import javax.ws.rs.core.Application;

import org.commonjava.aprox.bind.jaxrs.ui.UIServlet;
import org.commonjava.aprox.bind.jaxrs.util.AproxResteasyJsonProvider;
import org.commonjava.aprox.bind.jaxrs.util.CdiInjectorFactoryImpl;
import org.commonjava.aprox.bind.jaxrs.util.RequestScopeListener;
import org.jboss.resteasy.plugins.server.servlet.HttpServlet30Dispatcher;
import org.jboss.resteasy.spi.ResteasyDeployment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class AproxDeployment
    extends Application
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    public static final String API_PREFIX = "api";

    private static Set<Class<?>> PROVIDER_CLASSES;

    static
    {
        final Set<Class<?>> providers = new HashSet<>();
        providers.add( AproxResteasyJsonProvider.class );

        PROVIDER_CLASSES = providers;
    }

    @Inject
    private Instance<AproxResources> resources;

    @Inject
    private Instance<AproxDeploymentProvider> deployments;

    @Inject
    private UIServlet ui;

    private Set<Class<?>> resourceClasses;

    private Set<Class<?>> providerClasses = PROVIDER_CLASSES;

    private Set<AproxDeploymentProvider> deploymentProviders;

    protected AproxDeployment()
    {
    }

    public AproxDeployment( final Set<Class<?>> resourceClasses,
                            final Set<AproxDeploymentProvider> deploymentProviders, final UIServlet ui )
    {
        this.resourceClasses = resourceClasses;
        this.deploymentProviders = deploymentProviders;
        this.ui = ui;
        this.providerClasses = Collections.emptySet();
    }

    @PostConstruct
    public void cdiInit()
    {
        providerClasses = Collections.emptySet();
        resourceClasses = new HashSet<>();
        for ( final AproxResources aproxResources : resources )
        {
            resourceClasses.add( aproxResources.getClass() );
        }

        deploymentProviders = new HashSet<>();
        for ( final AproxDeploymentProvider fac : deployments )
        {
            logger.info( "Found deployment provider: {}", fac );
            deploymentProviders.add( fac );
        }
    }

    public DeploymentInfo getDeployment( final String contextRoot )
    {
        final ResteasyDeployment deployment = new ResteasyDeployment();

        //        deployment.getActualResourceClasses()
        //                  .addAll( resourceClasses );
        //
        //        deployment.getActualProviderClasses()
        //                  .addAll( providerClasses );

        deployment.setApplication( this );
        deployment.setInjectorFactoryClass( CdiInjectorFactoryImpl.class.getName() );

        final ServletInfo resteasyServlet = Servlets.servlet( "REST", HttpServlet30Dispatcher.class )
                                                    .setAsyncSupported( true )
                                                    .setLoadOnStartup( 1 )
                                                    .addMapping( "/api*" )
                                                    .addMapping( "/api/*" );

        final FilterInfo secFilter = Servlets.filter( "Security", SecurityFilter.class );

        final DeploymentInfo di =
            new DeploymentInfo().addListener( Servlets.listener( RequestScopeListener.class ) )
                                .setContextPath( contextRoot )
                                .addServletContextAttribute( ResteasyDeployment.class.getName(), deployment )
                                .addServlet( resteasyServlet )
                                .addFilter( secFilter )
                                .addFilterUrlMapping( secFilter.getName(), "/api/*", DispatcherType.REQUEST )
                                .setDeploymentName( "AProx" )
                                .setClassLoader( ClassLoader.getSystemClassLoader() );

        if ( deploymentProviders != null )
        {
            for ( final AproxDeploymentProvider deploymentFactory : deploymentProviders )
            {
                logger.info( "Adding deployments from: {}" + deploymentFactory.getClass()
                                                                              .getName() );
                final DeploymentInfo info = deploymentFactory.getDeploymentInfo();
                final Map<String, ServletInfo> servletInfos = info.getServlets();
                if ( servletInfos != null )
                {
                    for ( final Map.Entry<String, ServletInfo> si : servletInfos.entrySet() )
                    {
                        di.addServlet( si.getValue() );
                    }
                }

                final Map<String, FilterInfo> filterInfos = info.getFilters();
                if ( filterInfos != null )
                {
                    for ( final Map.Entry<String, FilterInfo> fi : filterInfos.entrySet() )
                    {
                        di.addFilter( fi.getValue() );
                    }
                }

                // TODO: More comprehensive merge...
            }
        }

        // Add UI servlet at the end so its mappings don't obscure any from add-ons.
        final ServletInfo uiServlet = Servlets.servlet( "UI", UIServlet.class )
                                              .setAsyncSupported( true )
                                              .setLoadOnStartup( 99 )
                                              .addMapping( "/*.html" )
                                              .addMapping( "/" )
                                              .addMapping( "/js/*" )
                                              .addMapping( "/css/*" )
                                              .addMapping( "/partials/*" )
                                              .addMapping( "/ui-addons/*" );

        uiServlet.setInstanceFactory( new ImmediateInstanceFactory<Servlet>( ui ) );
        di.addServlet( uiServlet );

        return di;
    }

    @Override
    public Set<Class<?>> getClasses()
    {
        final Set<Class<?>> classes = new HashSet<>( resourceClasses );
        classes.addAll( providerClasses );
        return classes;
    }

}
