/**
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * @author Arne Kepp, The Open Planning Project, Copyright 2008
 */

package org.geowebcache;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.geowebcache.config.Configuration;
import org.geowebcache.conveyor.Conveyor;
import org.geowebcache.conveyor.Conveyor.CacheResult;
import org.geowebcache.conveyor.ConveyorTile;
import org.geowebcache.demo.Demo;
import org.geowebcache.filter.request.RequestFilterException;
import org.geowebcache.grid.GridSetBroker;
import org.geowebcache.grid.OutsideCoverageException;
import org.geowebcache.layer.BadTileException;
import org.geowebcache.layer.TileLayer;
import org.geowebcache.layer.TileLayerDispatcher;
import org.geowebcache.mime.ImageMime;
import org.geowebcache.service.OWSException;
import org.geowebcache.service.Service;
import org.geowebcache.stats.RuntimeStats;
import org.geowebcache.storage.DefaultStorageFinder;
import org.geowebcache.storage.StorageBroker;
import org.geowebcache.util.ServletUtils;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.AbstractController;

/**
 * This is the main router for requests of all types.
 */
public class GeoWebCacheDispatcher extends AbstractController {
    private static Log log = LogFactory.getLog(org.geowebcache.GeoWebCacheDispatcher.class);

    public static final String TYPE_SERVICE = "service";
    
    public static final String TYPE_DEMO = "demo";

    public static final String TYPE_HOME = "home";
    
    private TileLayerDispatcher tileLayerDispatcher = null;
    
    private DefaultStorageFinder defaultStorageFinder = null;
    
    private GridSetBroker gridSetBroker = null;
    
    private StorageBroker storageBroker;
    
    private Configuration mainConfiguration;
    
    private RuntimeStats runtimeStats;
    
    private HashMap<String,Service> services = null;
    
    private byte[] blankTile = null; 
    
    private String servletPrefix = null;

    

    /** 
     * Should be invoked through Spring 
     * 
     * @param tileLayerDispatcher
     * @param gridSetBroker
     */
    public GeoWebCacheDispatcher(TileLayerDispatcher tileLayerDispatcher, 
            GridSetBroker gridSetBroker, StorageBroker storageBroker,
            Configuration mainConfiguration, RuntimeStats runtimeStats) {
        super();
        this.tileLayerDispatcher = tileLayerDispatcher;
        this.gridSetBroker = gridSetBroker;
        this.mainConfiguration = mainConfiguration;
        this.runtimeStats = runtimeStats;
        this.storageBroker = storageBroker;
        
        if(mainConfiguration.isRuntimeStatsEnabled()) {
            this.runtimeStats.start();
        } else {
            runtimeStats = null;
        }
    }

    public void setStorageBroker() {
       // This is just to force initialization
       log.debug("GeoWebCacheDispatcher received StorageBroker : " + storageBroker.toString());
    }

    public void setDefaultStorageFinder(DefaultStorageFinder defaultStorageFinder) {
        this.defaultStorageFinder = defaultStorageFinder;
    }
    
    /**
     * GeoServer and other solutions that embedded this dispatcher will prepend a
     * path, this is used to remove it.
     * 
     * @param servletPrefix
     */
    public void setServletPrefix(String servletPrefix) {
        if(! servletPrefix.startsWith("/")) {
            this.servletPrefix = "/" + servletPrefix; 
        } else {
            this.servletPrefix = servletPrefix;
        }
        
        log.info("Invoked setServletPrefix("+servletPrefix+")");
    }

    /**
     * Services convert HTTP requests into the internal grid representation and
     * specify what layer the response should come from.
     * 
     * The classpath is scanned for objects extending Service, thereby making it
     * easy to add new services.
     */
    @SuppressWarnings("unchecked")
    private void loadServices() {
        // Give all service objects direct access to the tileLayerDispatcher
        WebApplicationContext context = (WebApplicationContext) getApplicationContext();
        
        Map<String,Service> serviceBeans = (Map<String,Service>) context.getBeansOfType(Service.class);
        Iterator<Service> beanIter = serviceBeans.values().iterator();
        services = new HashMap<String,Service>();
        while (beanIter.hasNext()) {
            Service aService = (Service) beanIter.next();
            services.put(aService.getPathName(), aService);
        }
    }

    private void loadBlankTile() {        
        String blankTilePath = defaultStorageFinder.findEnvVar(DefaultStorageFinder.GWC_BLANK_TILE_PATH);
        
        if(blankTilePath != null) {
            File fh = new File(blankTilePath);
            if(fh.exists() && fh.canRead() && fh.isFile()) {
                long fileSize = fh.length();
                blankTile = new byte[(int) fileSize];
                
                int total = 0;
                FileInputStream fis = null;
                try {
                    fis = new FileInputStream(fh);
                    int read = 0;
                    while(read != -1) {
                        read = fis.read(blankTile, total, blankTile.length - total);
                        
                        if(read != -1)
                            total += read;
                    }
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    try {
                        fis.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                
                if(total == blankTile.length && total > 0) {
                    log.info("Loaded blank tile from " + blankTilePath);
                } else {
                    log.error("Failed to load blank tile from " + blankTilePath);
                }

                return;
            } else {
                log.error("" + blankTilePath + " does not exist or is not readable.");
            }
        }
        
        // Use the built-in one: 
        InputStream is = null;   
        try {
            is = GeoWebCacheDispatcher.class.getResourceAsStream("blank.png");
            blankTile = new byte[425];
            int ret = is.read(blankTile);
            log.info("Read " + ret + " from blank PNG file (expected 425).");
        } catch (IOException ioe) {
            log.error(ioe.getMessage());
        } finally {
            try {
                if(is != null) 
                    is.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        
    }
    
    /**
     * Spring function for MVC, this is the entry point for the application.
     * 
     * If a tile is requested the request will be handed off to
     * handleServiceRequest.
     * 
     */
    protected ModelAndView handleRequestInternal(HttpServletRequest request,
            HttpServletResponse response) throws Exception {

        // Break the request into components, {type, service name}
        String[] requestComps = null;
        try {
            String normalizedURI = request.getRequestURI().replaceFirst(request.getContextPath(), "");
            
            if(servletPrefix != null) {
                normalizedURI =  normalizedURI.replaceFirst(servletPrefix, ""); //getRequestURI().replaceFirst(request.getContextPath()+, "");
            }
             requestComps = parseRequest(normalizedURI);
            //requestComps = parseRequest(request.getRequestURI());
        } catch (GeoWebCacheException gwce) {
            writeError(response, 400, gwce.getMessage());
            return null;
        }

        try {
            if(requestComps == null || requestComps[0].equalsIgnoreCase(TYPE_HOME)) {
                handleFrontPage(request, response);
            } else if (requestComps[0].equalsIgnoreCase(TYPE_SERVICE)) {
                handleServiceRequest(requestComps[1], request, response);
            } else if (requestComps[0].equalsIgnoreCase(TYPE_DEMO) 
                    || requestComps[0].equalsIgnoreCase(TYPE_DEMO + "s")) {
                handleDemoRequest(requestComps[1],request, response);   
            } else {
                writeError(response, 404, "Unknown path: " + requestComps[0]);
            }
        } catch (Exception e) {
            // e.printStackTrace();
            if(e instanceof RequestFilterException) {
                
                RequestFilterException reqE = (RequestFilterException) e;
                reqE.setHttpInfoHeader(response);
                
                writeFixedResponse(response, reqE.getResponseCode(), reqE.getContentType(), reqE.getResponse(), CacheResult.OTHER);
            } else if(e instanceof OWSException) {
                OWSException owsE = (OWSException) e;
                writeFixedResponse(response, owsE.getResponseCode(), owsE.getContentType(), owsE.getResponse(), CacheResult.OTHER);
            } else {
                if(! (e instanceof BadTileException) || log.isDebugEnabled()) {
                    log.error(e.getMessage()+ " " + request.getRequestURL().toString());
                }
                
                writeError(response, 400, e.getMessage());
                
                if(! (e instanceof GeoWebCacheException) || log.isDebugEnabled()) {
                    e.printStackTrace();
                }
            }
        }
        return null;
    }

    /**
     * Destroy function, has to be referenced in bean declaration:
     * <bean ... destroy="destroy">...</bean>
     */
    public void destroy() {
        log.info("GeoWebCacheDispatcher.destroy() was invoked, shutting down.");
    }
    
    /**
     * Essentially this slices away the prefix, leaving type and request
     * 
     * @param servletPath
     * @return {type, service}ervletPrefix
     */
    private String[] parseRequest(String servletPath)
            throws GeoWebCacheException {
        String[] retStrs = new String[2];
        String[] splitStr = servletPath.split("/");
        
        if(splitStr == null || splitStr.length < 2) {
            return null;
        }
        
        retStrs[0] = splitStr[1];
        if(splitStr.length > 2) {
            retStrs[1] = splitStr[2];
        }
        return retStrs;
    }

    /**
     * This is the main method for handling service requests. See comments in
     * the code.
     * 
     * @param request
     * @param response
     * @throws Exception
     */
    private void handleServiceRequest(String serviceStr,
            HttpServletRequest request, HttpServletResponse response)
            throws Exception {

        Conveyor conv = null;

        // 1) Figure out what Service should handle this request
        Service service = findService(serviceStr);

        // 2) Find out what layer will be used and how
        conv = service.getConveyor(request, response);

        // Check where this should be dispatched
        if (conv.reqHandler == Conveyor.RequestHandler.SERVICE) {
            // A3 The service object takes it from here
            service.handleRequest(conv);

        } else {
            ConveyorTile convTile = (ConveyorTile) conv;

            // B3) Get the configuration that has to respond to this request
            TileLayer layer = tileLayerDispatcher.getTileLayer(convTile.getLayerId());
            
            // Save it for later
            convTile.setTileLayer(layer);
                        
            // Apply the filters
            layer.applyRequestFilters(convTile);

            // Keep the URI
            // tile.requestURI = request.getRequestURI();

            try {
                // A5) Ask the layer to provide the content for the tile
                layer.getTile(convTile);
                
                // A6) Write response
                writeData(convTile);
                
                // Alternatively: 
            } catch (OutsideCoverageException e) {
                writeEmpty(convTile, e.getMessage());
            }
        }
    }
       
    
    private void handleDemoRequest(String action, HttpServletRequest request, 
            HttpServletResponse response) throws GeoWebCacheException {
        Demo.makeMap(tileLayerDispatcher, gridSetBroker, action, request, response);        
    }
    
    /**
     * Helper function for looking up the service that should handle the
     * request.
     * 
     * @param request
     *            full HttpServletRequest
     * @return
     */
    private Service findService(String serviceStr) throws GeoWebCacheException {
        if (this.services == null) {
            loadServices();
            loadBlankTile();
        }

        // E.g. /wms/test -> /wms
        Service service = (Service) services.get(serviceStr);
        if (service == null) {
            if(serviceStr == null || serviceStr.length() == 0) {
                serviceStr = ", try service/&lt;name of service&gt;";
            } else {
                serviceStr = " \""+ serviceStr + "\"";
            }
            throw new GeoWebCacheException(
                    "Unable to find handler for service" + serviceStr);
        }
        return service;
    }
    
    /**
     * Create a minimalistic frontpage
     * 
     * @param request
     * @param response
     */
    private void handleFrontPage(HttpServletRequest request, HttpServletResponse response) {

        String baseUrl = null;
        
        if(request.getRequestURL().toString().endsWith("/") ||
                request.getRequestURL().toString().endsWith("home") ) {
            baseUrl = "";
        } else {
            String[] strs = request.getRequestURL().toString().split("/");
            baseUrl = strs[strs.length - 1]+ "/";
        }
        
        StringBuilder str = new StringBuilder();
        
        Package versionInfo = Package.getPackage("org.geowebcache");
        String version = versionInfo.getSpecificationVersion();
        String build = versionInfo.getImplementationVersion();
        if(version == null){
            version = "{NO VERSION INFO IN MANIFEST}";
        }
        if(build == null){
            build = "{NO BUILD INFO IN MANIFEST}";
        }
        
        str.append("<html>\n"+ServletUtils.gwcHtmlHeader("GWC Home") +"<body>\n" + ServletUtils.gwcHtmlLogoLink(baseUrl));
        str.append("<h3>Welcome to GeoWebCache version "+ version +", built "+ build +"</h3>\n");
        str.append("<p><a href=\"http://geowebcache.org\">GeoWebCache</a> is an advanced tile cache for WMS servers.");
        str.append("It supports a large variety of protocols and formats, including WMS-C, WMTS, KML, Google Maps and Virtual Earth.</p>");
        str.append("<h3>Automatically Generated Demos:</h3>\n");
        str.append("<ul><li><a href=\""+baseUrl+ "demo\">A list of all the layers and automatic demos</a></li></ul>\n");
        str.append("<h3>GetCapabilities:</h3>\n");
        str.append("<ul><li><a href=\""+baseUrl+"service/wmts?REQUEST=getcapabilities\">WMTS 1.0.0 GetCapabilities document</a></li>");
        str.append("<li><a href=\""+baseUrl+"service/wms?SERVICE=WMS&VERSION=1.1.1&REQUEST=getcapabilities&TILED=true\">WMS 1.1.1 GetCapabilities document</a></li>");
        str.append("<li><a href=\""+baseUrl+"service/tms/1.0.0\">TMS 1.0.0 document</a></li>");
        str.append("<li>Note that the latter will only work with clients that are ");
        str.append("<a href=\"http://wiki.osgeo.org/wiki/WMS_Tiling_Client_Recommendation\">WMS-C capable</a>.</li>\n");
        str.append("<li>Omitting tiled=true from the URL will omit the TileSet elements.</li></ul>\n");
        if(runtimeStats != null) {
            str.append("<h3>Runtime Statistics</h3>\n");
            str.append(runtimeStats.getHTMLStats());
        }
        str.append("</body></html>\n");
        
        writePage(response, 200, str.toString());
    }

    /**
     * Wrapper method for writing an error back to the client, and logging it at
     * the same time.
     * 
     * @param response
     *            where to write to
     * @param httpCode
     *            the HTTP code to provide
     * @param errorMsg
     *            the actual error message, human readable
     */
    private void writeError(HttpServletResponse response, int httpCode, String errorMsg) {
        log.debug(errorMsg);
        
        
        errorMsg =  "<html>\n"+ServletUtils.gwcHtmlHeader("GWC Error") +"<body>\n" 
        + ServletUtils.gwcHtmlLogoLink("../") + "<h4>"+httpCode+": "+ServletUtils.disableHTMLTags(errorMsg)+"</h4>" + "</body></html>\n";
        writePage(response, httpCode, errorMsg);
    }
        
    private void writePage(HttpServletResponse response, int httpCode, String message) {
        writeFixedResponse(response, httpCode, "text/html", message.getBytes(), CacheResult.OTHER);      
    }

    /**
     * Happy ending, sets the headers and writes the response back to the
     * client.
     */
    private void writeData(ConveyorTile tile) throws IOException {
        if(tile.getLayer().useETags()) {
            String ifNoneMatch = tile.servletReq.getHeader("If-None-Match");
            String hexTag = Long.toHexString(tile.getTSCreated());
            
            if(ifNoneMatch != null) {    
                if(ifNoneMatch.equals(hexTag)) {
                    tile.servletResp.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
                    return;
                }
            }
            
            // If we get here, we want ETags but the client did not have the tile.
            tile.servletResp.setHeader("ETag", hexTag);
        } 
        
        writeFixedResponse(tile.servletResp, 200, tile.getMimeType().getMimeType(), tile.getContent(), tile.getCacheResult());
    }
    
    /**
     * Writes a transparent, 8 bit PNG to avoid having clients like OpenLayers
     * showing lots of pink tiles
     */
    private void writeEmpty(ConveyorTile tile, String message) {
        tile.servletResp.setHeader("geowebcache-message", message);
        TileLayer layer = tile.getLayer();
        if(layer != null) {
            layer.setExpirationHeader(tile.servletResp, (int) tile.getTileIndex()[2]);
            
            if(layer.useETags()) {
                String ifNoneMatch = tile.servletReq.getHeader("If-None-Match");
                if(ifNoneMatch != null && ifNoneMatch.equals("gwc-blank-tile")) {
                    tile.servletResp.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
                    return;
                } else {
                    tile.servletResp.setHeader("ETag", "gwc-blank-tile");
                }
            }
        }

        writeFixedResponse(tile.servletResp, 200, ImageMime.png.getMimeType(), this.blankTile, CacheResult.OTHER);
    }
    
    private void writeFixedResponse(HttpServletResponse response, int httpCode, String contentType, byte[] data, CacheResult cacheRes) {
        response.setStatus(httpCode);
        response.setContentType(contentType);
        
        if(data != null) {
            response.setContentLength(data.length);
            
            try {
                OutputStream os = response.getOutputStream();
                os.write(data);
                
                runtimeStats.log(data.length, cacheRes);
                
            } catch (IOException ioe) {
                log.debug("Caught IOException: " + ioe.getMessage() + "\n\n" + ioe.toString());
            }
        }
    }
}
