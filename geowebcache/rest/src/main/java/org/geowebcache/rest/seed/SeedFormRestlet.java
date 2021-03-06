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
 * @author Arne Kepp / The Open Planning Project 2008 
 */
package org.geowebcache.rest.seed;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.Map.Entry;

import org.geowebcache.GeoWebCacheException;
import org.geowebcache.grid.BoundingBox;
import org.geowebcache.grid.GridSubset;
import org.geowebcache.layer.TileLayer;
import org.geowebcache.mime.ImageMime;
import org.geowebcache.mime.MimeType;
import org.geowebcache.rest.GWCRestlet;
import org.geowebcache.rest.RestletException;
import org.geowebcache.seed.GWCTask;
import org.geowebcache.seed.SeedRequest;
import org.geowebcache.seed.TileBreeder;
import org.geowebcache.seed.GWCTask.TYPE;
import org.geowebcache.storage.TileRange;
import org.geowebcache.util.ServletUtils;
import org.restlet.data.Form;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Request;
import org.restlet.data.Response;
import org.restlet.data.Status;

public class SeedFormRestlet extends GWCRestlet {
    //private static Log log = LogFactory.getLog(org.geowebcache.rest.seed.SeedFormRestlet.class);
    
    private TileBreeder seeder;

    public void handle(Request request, Response response){
        Method met = request.getMethod();
        try {
            if (met.equals(Method.GET)) {
                doGet(request, response);
            } else if(met.equals(Method.POST)) {
                try {
                    doPost(request, response);
                } catch (GeoWebCacheException e) {
                    throw new RestletException(e.getMessage(), Status.CLIENT_ERROR_BAD_REQUEST);
                }
            } else {
                throw new RestletException("Method not allowed", Status.CLIENT_ERROR_METHOD_NOT_ALLOWED);
            }
        } catch (RestletException re) {
            response.setEntity(re.getRepresentation());
            response.setStatus(re.getStatus());
        }
    }
    
    public void doGet(Request request, Response response) throws RestletException {
        //String layerName = (String) request.getAttributes().get("layer");
        String layerName = null;
        try {
            layerName = URLDecoder.decode((String) request.getAttributes().get("layer"), "UTF-8");
        } catch (UnsupportedEncodingException uee) { }
        
        TileLayer tl;
        try {
            tl = seeder.findTileLayer(layerName);
        } catch (GeoWebCacheException e) {
            throw new RestletException(e.getMessage(), Status.CLIENT_ERROR_BAD_REQUEST);
        }
       
        response.setEntity(makeFormPage(tl), MediaType.TEXT_HTML);
    }
    
    public void doPost(Request req, Response resp) 
    throws RestletException, GeoWebCacheException {
        String layerName = null;
        try {
            layerName = URLDecoder.decode((String) req.getAttributes().get("layer"), "UTF-8");
        } catch (UnsupportedEncodingException uee) { }
        
        Form form = req.getEntityAsForm();

        if(form == null) {
            throw new RestletException("Unable to parse form result.", Status.CLIENT_ERROR_BAD_REQUEST);
        }
        
        //String layerName = form.getFirst("layerName").getValue();
        
        TileLayer tl;
        try {
            tl = seeder.findTileLayer(layerName);
        } catch (GeoWebCacheException e) {
            throw new RestletException(e.getMessage(), Status.CLIENT_ERROR_BAD_REQUEST);
        }
        
        if (form != null && form.getFirst("kill_thread") != null) {
            handleKillThreadPost(form, tl, resp);
        } else if (form != null && form.getFirst("minX") != null) {
            handleDoSeedPost(form, tl, resp);
        } else {
            throw new RestletException(
                    "Unknown or malformed request. Please try again, somtimes the form "
                    +"is not properly received. This frequently happens on the first POST "
                    +"after a restart. The POST was to " + req.getResourceRef().getPath(), 
                    Status.CLIENT_ERROR_BAD_REQUEST );
        }
    }
        
    private String makeFormPage(TileLayer tl) {
        
        StringBuilder doc = new StringBuilder();
        
        makeHeader(doc);
        
        makeTaskList(doc, tl);
        
        makeWarningsAndHints(doc, tl);
        
        makeFormHeader(doc, tl);
        
        makeThreadCountPullDown(doc);
        
        makeTypePullDown(doc);
        
        makeGridSetPulldown(doc, tl);
        
        makeFormatPullDown(doc, tl);
        
        makeZoomStartPullDown(doc, tl);
        
        makeZoomStopPullDown(doc, tl);
        
        //TODO make list of modifiable parameter combos
        
        makeBboxFields(doc);
        
        makeSubmit(doc);
        
        makeFormFooter(doc);  
        
        makeFooter(doc);
        
        return doc.toString();
    }
    
    private String makeResponsePage(TileLayer tl) {
        
        StringBuilder doc = new StringBuilder();
        
        makeHeader(doc);
        
        doc.append("<h3>Task submitted</h3>\n");
        
        doc.append("<p>Below you can find a list of currently executing threads, take the numbers with a grain of salt");
        doc.append(" until the thread has had a chance to run for a few minutes. ");
        
        makeTaskList(doc, tl);
        
        makeFooter(doc);
        
        return doc.toString();
    }
    
    private void makeTypePullDown(StringBuilder doc) {
        doc.append("<tr><td>Type of operation:</td><td>\n");
        Map<String,String> keysValues = new TreeMap<String,String>();
        
        keysValues.put("Truncate - remove tiles","truncate");
        keysValues.put("Seed - generate missing tiles","seed");
        keysValues.put("Reseed - regenerate all tiles", "reseed");

        makePullDown(doc, "type", keysValues, "Seed - generate missing tiles");
        doc.append("</td></tr>\n");
    }

    private void makeThreadCountPullDown(StringBuilder doc) {
        doc.append("<tr><td>Number of threads to use:</td><td>\n");
        Map<String,String> keysValues = new TreeMap<String,String>();
        
        for(int i=1; i<17; i++) {
            if(i < 10) {
                keysValues.put("0"+Integer.toString(i), "0"+Integer.toString(i));
            } else {
                keysValues.put(Integer.toString(i), Integer.toString(i));
            }
        }
        makePullDown(doc, "threadCount", keysValues, Integer.toString(2));
        doc.append("</td></tr>\n");
    }

    private void makeBboxFields(StringBuilder doc) {
        doc.append("<tr><td valign=\"top\">Bounding box:</td><td>\n");
        makeTextInput(doc, "minX", 6);
        makeTextInput(doc, "minY", 6);
        makeTextInput(doc, "maxX", 6);
        makeTextInput(doc, "maxY", 6);
        doc.append("</br>These are optional, approximate values are fine.");
        doc.append("</td></tr>\n");
    }
    
    private void makeBboxHints(StringBuilder doc, TileLayer tl) {
        Iterator<Entry<String, GridSubset>> iter = tl.getGridSubsets().entrySet().iterator();
        
        //int minStart = Integer.MAX_VALUE;
        //int maxStop = Integer.MIN_VALUE;
        
        while(iter.hasNext()) {
            Entry<String, GridSubset> entry = iter.next();
            doc.append("<li>"+entry.getKey().toString()
                    +":   "+entry.getValue().getOriginalExtent().toString()+"</li>\n");
        }
        
    }

    private void makeTextInput(StringBuilder doc, String id, int size) {
        doc.append("<input name=\""+id+"\" type=\"text\" size=\""+size+"\" />\n");
    }

    private void makeSubmit(StringBuilder doc) {
        doc.append("<tr><td></td><td><input type=\"submit\" value=\"Submit\"></td></tr>\n");
    }

    private void makeZoomStopPullDown(StringBuilder doc, TileLayer tl) {
        doc.append("<tr><td>Zoom stop:</td><td>\n");
        makeZoomPullDown(doc, false, tl);
        doc.append("</td></tr>\n");
    }

    private void makeZoomStartPullDown(StringBuilder doc, TileLayer tl) {
        doc.append("<tr><td>Zoom start:</td><td>\n");
        makeZoomPullDown(doc, true, tl);
        doc.append("</td></tr>\n");
    }
    
    private void makeZoomPullDown(StringBuilder doc, boolean isStart, TileLayer tl) {
        Map<String,String> keysValues = new TreeMap<String,String>();
        
        Iterator<Entry<String, GridSubset>> iter = tl.getGridSubsets().entrySet().iterator();
        
        int minStart = Integer.MAX_VALUE;
        int maxStop = Integer.MIN_VALUE;
        
        while(iter.hasNext()) {
            Entry<String, GridSubset> entry = iter.next();
            
            int start = entry.getValue().getZoomStart();
            int stop = entry.getValue().getZoomStop();
            
            if(start < minStart) {
                minStart = start;
            }
            if(stop > maxStop) {
                maxStop = stop;
            }
        }
        
        for(int i=minStart; i<=maxStop; i++) {
            if(i < 10) {
                keysValues.put("0" + Integer.toString(i), "0" + Integer.toString(i));
            } else {
                keysValues.put(Integer.toString(i), Integer.toString(i));
            }
        }
        
        if(isStart) {
            if(minStart < 10) {
                makePullDown(doc, "zoomStart", keysValues, "0" + Integer.toString(minStart)); 
            } else {
                makePullDown(doc, "zoomStart", keysValues, Integer.toString(minStart));
            }
            
        } else {
            int midStop = ( minStart + maxStop )/ 2;
            if(midStop < 10) {
                makePullDown(doc, "zoomStop", keysValues, "0" + Integer.toString(midStop));
            } else {
                makePullDown(doc, "zoomStop", keysValues, Integer.toString(midStop));  
            }
        }
    }

    private void makeFormatPullDown(StringBuilder doc, TileLayer tl) {
        doc.append("<tr><td>Format:</td><td>\n");
        Map<String,String> keysValues = new TreeMap<String,String>();
        
        Iterator<MimeType> iter = tl.getMimeTypes().iterator();
        
        while(iter.hasNext()) {
            MimeType mime = iter.next();
            keysValues.put(mime.getFormat(), mime.getFormat());
        }
        
        makePullDown(doc, "format", keysValues, ImageMime.png.getFormat());
        doc.append("</td></tr>\n");
    }

    private void makeGridSetPulldown(StringBuilder doc, TileLayer tl) {
        doc.append("<tr><td>Grid Set:</td><td>\n");
        Map<String,String> keysValues = new TreeMap<String,String>();
        
        Iterator<String> iter = tl.getGridSubsets().keySet().iterator();
        
        String firstGridSetId = null;
        while(iter.hasNext()) {
            String gridSetId = iter.next();
            if(firstGridSetId == null) {
                firstGridSetId = gridSetId;
            }
            keysValues.put(gridSetId,gridSetId);
        }
        
        makePullDown(doc, "gridSetId", keysValues, firstGridSetId);
        doc.append("</td></tr>\n");
    }

    private void makePullDown(StringBuilder doc, String id, Map<String,String> keysValues, String defaultKey) {
        doc.append("<select name=\""+id+"\">\n");
        
        Iterator<Entry<String,String>> iter = keysValues.entrySet().iterator();
        
        while(iter.hasNext()) {
            Entry<String,String> entry = iter.next();
            if(entry.getKey().equals(defaultKey)) {
                doc.append("<option value=\""+entry.getValue()+"\" selected=\"selected\">"+entry.getKey()+"</option>\n");
            } else {
                doc.append("<option value=\""+entry.getValue()+"\">"+entry.getKey()+"</option>\n");
            }
        }
        
        doc.append("</select>\n");
    }
    
    private void makeFormHeader(StringBuilder doc, TileLayer tl) {
        doc.append("<h4>Create a new task:</h4>\n");
        doc.append("<form id=\"seed\" action=\"./"+tl.getName()+"\" method=\"post\">\n");
        doc.append("<table border=\"0\" cellspacing=\"10\">\n");
    }
    
    private void makeFormFooter(StringBuilder doc) {
        doc.append("</table>\n");
        doc.append("</form>\n");
    }
    
    private void makeHeader(StringBuilder doc) {
        doc.append("<html>\n"+ServletUtils.gwcHtmlHeader("GWC Seed Form") +"<body>\n" + ServletUtils.gwcHtmlLogoLink("../../"));    
    }
    
    private void makeWarningsAndHints(StringBuilder doc, TileLayer tl) {
        doc.append("<h4>Please note:</h4><ul>\n"
                + "<li>This minimalistic interface does not check for correctness.</li>\n"
                + "<li>Seeding past zoomlevel 20 is usually not recommended.</li>\n"
                + "<li>Truncating KML will also truncate all KMZ archives.</li>\n"
        	+ "<li>Please check the logs of the container to look for error messages and progress indicators.</li>\n"
        	+ "</ul>\n");
        
        doc.append("Here are the max bounds, if you do not specify bounds these will be used.\n");
        doc.append("<ul>\n");
        makeBboxHints(doc, tl);
        doc.append("</ul>\n");
    }
        
    private void makeTaskList(StringBuilder doc, TileLayer tl) {
        doc.append("<h4>List of currently executing tasks:</h4>\n");
        
        Iterator<Entry<Long, GWCTask>> iter = seeder.getRunningTasksIterator();
        
        boolean tasks = false;
        if(! iter.hasNext()) {
            doc.append("<ul><li><i>none</i></li></ul>\n");
        } else {
            doc.append("<table border=\"0\" cellspacing=\"10\">");
            doc.append("<tr style=\"font-weight: bold;\"><td>Id</td><td>Layer</td><td>Type</td><td>Estimated number of tiles</td>"
                    +"<td>Tiles completed</td><td>Time remaining</td><td>Threads</td><td>&nbsp;</td><tr>");
            tasks = true;
        }
        
        while(iter.hasNext()) {
            Entry<Long, GWCTask> entry = iter.next();
            GWCTask task = entry.getValue();
            
            String timeRemaining;
            long time = task.getTimeRemaining();
            
            if(task.getTilesDone() < 50) {
                timeRemaining= " Estimating...";
            } else if(time == -2 && task.getTilesDone() < task.getTilesTotal()) {
                timeRemaining= " A decade or three.";
            } else if(time > (60*60*24)) {
                timeRemaining= "" + (time / (60*60*24)) + " day(s)";
            } else if(time > 60*60) {
                timeRemaining = "" + (time / (60*60)) + " hour(s)";
            } else if(time > 60) {
                timeRemaining = "" +(time / 60) + " minute(s)";
            } else {
                timeRemaining = "" +(time) + " second(s)";
            }
            
            
            doc.append("<tr>"
                    + "<td>" + entry.getKey() + "</td>"
                    + "<td>" + task.getLayerName() + "</td>"
                    + "<td>" + task.getType() + "</td>"
                    + "<td>" + task.getTilesTotalStr() + "</td>"
                    + "<td>" + task.getTilesDone() + "</td>"
                    + "<td>" + timeRemaining + "</td>"
                    + "<td>(Thread " + (task.getThreadOffset()+1) + " of " + task.getThreadCount() + ") </td>"
                    + "<td>" + makeThreadKillForm(entry.getKey(), tl) + "</td><tr>");
        }
        
        if(tasks) {
            doc.append("</table>");
        }
        doc.append("<p><a href=\"./"+tl.getName()+"\">Refresh list</a></p>\n");
    }
    
    private String makeThreadKillForm(Long key, TileLayer tl) {
        String ret =  "<form form id=\"kill\" action=\"./"+tl.getName()+"\" method=\"post\">"
            	+ "<input type=\"hidden\" name=\"kill_thread\"  value=\"1\" />"
                + "<input type=\"hidden\" name=\"thread_id\"  value=\""+key+"\" />"
                + "<span><input style=\"padding: 0; margin-bottom: -12px; border: 1;\"type=\"submit\" value=\"Kill Thread\"></span>"
            	+ "</form>";
            		
        return ret;
    }

    private void makeFooter(StringBuilder doc) {
        doc.append("</body></html>\n");
    }
    
    private void handleKillThreadPost(Form form, TileLayer tl, Response resp) {
        String id = form.getFirstValue("thread_id");
        
        StringBuilder doc = new StringBuilder();
        
        makeHeader(doc);
        
        if(seeder.terminateGWCTask(Long.parseLong(id))) {
            doc.append("<ul><li>Requested to terminate task " + id + ".</li></ul>");
        } else {
            doc.append("<ul><li>Sorry, either task " + id 
                    + " has not started yet, or it is a truncate task that cannot be interrutped.</li></ul>");;
        }
        
        doc.append("<p><a href=\"./"+tl.getName()+"\">Go back</a></p>\n");
        
        resp.setEntity(doc.toString(), MediaType.TEXT_HTML);
    }
    

    private void handleDoSeedPost(Form form, TileLayer tl, Response resp)
            throws RestletException, GeoWebCacheException {
        BoundingBox bounds = null;
        
        if (form.getFirst("minX").getValue() != null) {
            bounds = new BoundingBox(
                    parseDouble(form, "minX"), 
                    parseDouble(form, "minY"), 
                    parseDouble(form, "maxX"),
                    parseDouble(form, "maxY"));
        }
        
        String gridSetId = form.getFirst("gridSetId").getValue();

        int threadCount = Integer.parseInt(form.getFirst("threadCount").getValue());
        int zoomStart = Integer.parseInt(form.getFirst("zoomStart").getValue());
        int zoomStop = Integer.parseInt(form.getFirst("zoomStop").getValue());

        String format = form.getFirst("format").getValue();

        TYPE type = GWCTask.TYPE.valueOf(form.getFirst("type").getValue().toUpperCase());

        SeedRequest sr = new SeedRequest(tl.getName(), bounds, gridSetId,
                threadCount, zoomStart, zoomStop, format, type, null);
        
        TileRange tr = TileBreeder.createTileRange(sr, tl);
        
        GWCTask[] tasks;
        try {
            tasks = seeder.createTasks(tr, tl, sr.getType(), sr.getThreadCount(), sr
                    .getFilterUpdate());
        } catch (GeoWebCacheException e) {
            throw new RestletException(e.getMessage(), Status.SERVER_ERROR_INTERNAL);
        }
        
        
        seeder.dispatchTasks(tasks);

        // Give the thread executor a chance to run
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            // Ok, no worries
        }

        resp.setEntity(this.makeResponsePage(tl), MediaType.TEXT_HTML);
    }
    
    private static double parseDouble(Form form, String key) throws RestletException {
        String value = form.getFirst(key).getValue();
        if(value == null || value.length() == 0)
            throw new RestletException("Missing value for " + key, 
                    Status.CLIENT_ERROR_BAD_REQUEST);
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException nfe) {
            throw new  RestletException("Value for " + key + " is not a double", 
                    Status.CLIENT_ERROR_BAD_REQUEST);
        }
    }
    
    public void setTileBreeder(TileBreeder seeder) {
        this.seeder = seeder;
    }
    
}
