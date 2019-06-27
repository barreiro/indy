package org.commonjava.indy.sli.jaxrs;

import org.commonjava.indy.sli.metrics.GoldenSignalsMetricSet;
import org.slf4j.MDC;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.lang.Boolean.parseBoolean;
import static java.lang.Integer.parseInt;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.commonjava.indy.bind.jaxrs.RequestContextHelper.HTTP_METHOD;
import static org.commonjava.indy.bind.jaxrs.RequestContextHelper.HTTP_STATUS;
import static org.commonjava.indy.bind.jaxrs.RequestContextHelper.METADATA_CONTENT;
import static org.commonjava.indy.bind.jaxrs.RequestContextHelper.PACKAGE_TYPE;
import static org.commonjava.indy.bind.jaxrs.RequestContextHelper.REQUEST_LATENCY_NS;
import static org.commonjava.indy.bind.jaxrs.RequestContextHelper.REST_ENDPOINT_PATH;
import static org.commonjava.indy.bind.jaxrs.RequestContextHelper.getContext;
import static org.commonjava.indy.pkg.PackageTypeConstants.PKG_TYPE_MAVEN;
import static org.commonjava.indy.pkg.PackageTypeConstants.PKG_TYPE_NPM;
import static org.commonjava.indy.sli.metrics.GoldenSignalsMetricSet.FN_CONTENT;
import static org.commonjava.indy.sli.metrics.GoldenSignalsMetricSet.FN_CONTENT_LISTING;
import static org.commonjava.indy.sli.metrics.GoldenSignalsMetricSet.FN_CONTENT_MAVEN;
import static org.commonjava.indy.sli.metrics.GoldenSignalsMetricSet.FN_CONTENT_NPM;
import static org.commonjava.indy.sli.metrics.GoldenSignalsMetricSet.FN_METADATA;
import static org.commonjava.indy.sli.metrics.GoldenSignalsMetricSet.FN_METADATA_MAVEN;
import static org.commonjava.indy.sli.metrics.GoldenSignalsMetricSet.FN_METADATA_NPM;
import static org.commonjava.indy.sli.metrics.GoldenSignalsMetricSet.FN_PROMOTION;
import static org.commonjava.indy.sli.metrics.GoldenSignalsMetricSet.FN_REPO_MGMT;
import static org.commonjava.indy.sli.metrics.GoldenSignalsMetricSet.FN_TRACKING_RECORD;

@ApplicationScoped
public class GoldenSignalsFilter
    implements Filter
{
    @Inject
    private GoldenSignalsMetricSet metricSet;

    @Override
    public void init( final FilterConfig filterConfig )
    {
    }

    @Override
    public void doFilter( final ServletRequest servletRequest, final ServletResponse servletResponse,
                          final FilterChain filterChain )
            throws IOException, ServletException
    {
        long start = System.nanoTime();

        try
        {
            filterChain.doFilter( servletRequest, servletResponse );
        }
        finally
        {
            long end = System.nanoTime();
            MDC.put( REQUEST_LATENCY_NS, String.valueOf( end - start ) );

            Set<String> functions = new HashSet<>( getFunctions() );
            boolean error = isError();

            functions.forEach( function -> metricSet.function( function ).ifPresent( ms -> {
                ms.latency( end-start ).call();
                if ( error )
                {
                    ms.error();
                }
            } ) );
        }
    }

    private boolean isError()
    {
        int status = parseInt( getContext( HTTP_STATUS, "200" ) );
        return status > 499;
    }

    private static final Set<String> MODIFY_METHODS = new HashSet<>( asList( "POST", "PUT", "DELETE" ) );

    private List<String> getFunctions()
    {
        String restPath = getContext( REST_ENDPOINT_PATH );
        String method = getContext( HTTP_METHOD );

        if ( restPath.matches( "/api/promotion/.+/promote" ) )
        {
            // this is a promotion request
            return singletonList( FN_PROMOTION );
        }
        else if ( restPath.matches( "/api/admin/stores/.+" ) )
        {
            if ( MODIFY_METHODS.contains( method ))
            {
                // this is a store modification request
                return singletonList( FN_REPO_MGMT );
            }
        }
        else if ( restPath.matches( "/api/browse/.+" ) )
        {
            // this is a browse / list request
            return singletonList( FN_CONTENT_LISTING );
        }
        else if ( restPath.matches( "/api/folo/admin/[^/]+/(record|report)" ) )
        {
            // this is a request for a tracking record
            return singletonList( FN_TRACKING_RECORD );
        }
        else if ( restPath.matches( "/api/(content/.+|folo/track/[^/]+/.+)" ) )
        {
            boolean isMetadata = parseBoolean( getContext( METADATA_CONTENT, "false" ) );
            String packageType = getContext( PACKAGE_TYPE );

            if ( PKG_TYPE_MAVEN.equals( packageType ) )
            {
                return isMetadata ?
                        asList( FN_METADATA, FN_METADATA_MAVEN ) :
                        asList( FN_CONTENT, FN_CONTENT_MAVEN );
            }
            else if ( PKG_TYPE_NPM.equals( packageType ) )
            {
                return isMetadata ?
                        asList( FN_METADATA, FN_METADATA_NPM ) :
                        asList( FN_CONTENT, FN_CONTENT_NPM );
            }
        }

        return emptyList();
    }

    @Override
    public void destroy()
    {
    }
}