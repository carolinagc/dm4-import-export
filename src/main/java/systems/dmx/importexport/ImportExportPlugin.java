package systems.dmx.importexport;

import com.sun.jersey.core.util.Base64;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import javax.ws.rs.*;
import javax.xml.stream.XMLStreamException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.security.AccessControlException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import systems.dmx.core.Assoc;
import systems.dmx.core.RelatedTopic;
import systems.dmx.core.Topic;
import systems.dmx.core.TopicType;
import systems.dmx.core.model.AssocModel;
import systems.dmx.core.model.ChildTopicsModel;
import systems.dmx.core.model.PlayerModel;
import systems.dmx.core.model.SimpleValue;
import systems.dmx.core.model.TopicModel;
import systems.dmx.core.model.topicmaps.ViewAssoc;
import systems.dmx.core.model.topicmaps.ViewProps;
import systems.dmx.core.model.topicmaps.ViewTopic;
import systems.dmx.core.osgi.PluginActivator;
import systems.dmx.core.service.Inject;
import systems.dmx.core.service.Transactional;
import systems.dmx.core.util.DMXUtils;
import systems.dmx.files.FilesService;
import systems.dmx.files.UploadedFile;
import systems.dmx.topicmaps.Topicmap;
import systems.dmx.topicmaps.TopicmapsService;
import systems.dmx.workspaces.WorkspacesService;

@Path("/import-export")
@Produces("application/json")
public class ImportExportPlugin extends PluginActivator {

    @Inject
    private TopicmapsService topicmaps;
    @Inject
    private FilesService file;
    @Inject
    private WorkspacesService workspaces;

    private Logger log = Logger.getLogger(getClass().getName());

    private static final String DM4_TIME_CREATED = "dmx.time.created";
    private static final String DM4_TIME_MODIFIED = "dmx.time.modified";

    // Zotero Report Column Header Names seem to be internationalized...
    private static final String ZOTERO_ENTRY_TYPE_COLUMN_KEY = "Typ";
    private static final String ZOTERO_ENTRY_URL_COLUMN_KEY = "URL";
    private static final String ZOTERO_MODIFIED_AT_COLUMN_KEY = "Geändert am";
    private static final String ZOTERO_ADDED_AT_COLUMN_KEY = "Hinzugefügt am";

    // Service implementation //

    @GET
    @Transactional
    @Path("/configured/content")
    public Topic exportConfiguredTopicTypes() {
        String jsonFileName = "dmx-import-export-configured-"+new Date().toString()+".txt";
        InputStream in = null;
        try {
            log.info("######## Start exporting topics of all configured topic and assoc types to JSON ######### ");
            JSONObject json = new JSONObject();
            // 1) fetch all workspace topics
            addWorkspaceTopicsToExport(json);
            // 2) fetch configured assoc and topic types to be included in export
            List<RelatedTopic> configuredTypes = getConfiguredTopicTypesForExport();
            List<RelatedTopic> configuredAssocTypes = getConfiguredAssocTypesForExport();
            // 3) export topics and associations of configured topic types
            json.put("topics", new JSONArray());
            for (RelatedTopic type : configuredTypes) {
                exportTopicsAndAssocsToJSON(type.getUri(), configuredAssocTypes, json);
            }
            // 4) export topicmaps topics with their current workspace assignment
            List<Topic> topicmaps = dmx.getTopicsByType("dmx.topicmaps.topicmap");
            json.put("topicmaps", new JSONArray());
            for (Topic topicmap : topicmaps) {
                log.info("### Exporting topicmap \"" + topicmap.getSimpleValue() + "\" ...");
                exportTopicmapToJSON(topicmap, json);
            }
            in = new ByteArrayInputStream(json.toString().getBytes("UTF-8"));
        } catch (JSONException ex) {
            Logger.getLogger(ImportExportPlugin.class.getName()).log(Level.SEVERE, null, ex);
        } catch (UnsupportedEncodingException ex) {
            Logger.getLogger(ImportExportPlugin.class.getName()).log(Level.SEVERE, null, ex);
        }
        // 5) Write export file
        return file.createFile(in, file.pathPrefix() + "/" + jsonFileName);
    }

    private void addWorkspaceTopicsToExport(JSONObject json) {
        try {
            json.put("workspaces", new JSONArray());
            Iterable<Topic> allWorksapces = dmx.getTopicsByType("dmx.workspaces.workspace");
            Iterator<Topic> workspaces = allWorksapces.iterator();
            log.info("### Exporting workspace topics ...");
            while (workspaces.hasNext()) {
                try {
                    JSONArray jsonTopics = json.getJSONArray("workspaces");
                    jsonTopics.put(workspaces.next().toJSON());
                } catch (AccessControlException ex) {
                    log.warning("### Workspace read permission denied => " + ex.getLocalizedMessage().toString());
                }
            }
        } catch (JSONException ex) {
            Logger.getLogger(ImportExportPlugin.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    private List<RelatedTopic> getConfiguredTopicTypesForExport() {
        Topic plugin = dmx.getTopicByUri("systems.dmx.import-export");
        log.info("Loaded " + plugin.getSimpleValue() + " plugin topic for inspecting export type configuration");
        List<RelatedTopic> configuredTypes = plugin.getRelatedTopics("dmx.core.association", "dmx.core.default",
                "dmx.core.default", "dmx.core.topic_type");
        return configuredTypes;
    }

    private List<RelatedTopic> getConfiguredAssocTypesForExport() {
        Topic plugin = dmx.getTopicByUri("systems.dmx.import-export");
        List<RelatedTopic> configuredAssocTypes = plugin.getRelatedTopics("dmx.core.association", "dmx.core.default",
                "dmx.core.default", "dmx.core.assoc_type");
        return configuredAssocTypes;
    }

    private void exportTopicmapToJSON(Topic topicmapTopic, JSONObject json) {
        try {
            JSONArray jsonTopics = json.getJSONArray("topicmaps");
            // fetch topicmap with all children
            Topicmap topicmap = topicmaps.getTopicmap(topicmapTopic.getId(), true);
            // fetch workspace assignment for topicmap
            Topic ws = workspaces.getAssignedWorkspace(topicmapTopic.getId());
            ws.loadChildTopics();
            // add workspace topic to Topicmap JSONObject
            JSONObject tm = topicmap.toJSON();
            tm.put("workspace", ws.toJSON());
            // add topicmap + workspace topic to export file
            jsonTopics.put(tm);
        } catch (AccessControlException ex) {
            log.warning("Topicmap read permission denied => " + ex.getLocalizedMessage().toString());
        } catch (JSONException ex) {
            Logger.getLogger(ImportExportPlugin.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    @GET
    @Transactional
    @Path("/content/json/{typeUri}")
    public Topic exportTopicsAndAssocsToJSON(@PathParam("typeUri") String typeUri, List<RelatedTopic> assocTypes, JSONObject givenJson) {
        try {
            log.info("### Exporting topics of type \""+typeUri+"\" with associations ...");
            JSONObject json = (givenJson != null) ? givenJson : new JSONObject();
            Iterable<Topic> allTopics = dmx.getTopicsByType(typeUri);
            Iterator<Topic> topics = allTopics.iterator();
            while (topics.hasNext()) {
                try {
                    JSONArray jsonTopics = json.getJSONArray("topics");
                    JSONObject topic = createTopicWithAssociationsJSON(topics.next(), assocTypes);
                    if (topic != null) jsonTopics.put(topic);
                } catch (AccessControlException ex) {
                    log.warning("### Topic read permission denied => " + ex.getLocalizedMessage().toString());
                }
            }
            if (givenJson == null) {
                InputStream in = new ByteArrayInputStream(json.toString().getBytes("UTF-8"));
                String jsonFileName = "dmx-"+typeUri + "-topics-"+new Date().toString()+".txt";
                return file.createFile(in, file.pathPrefix() + "/" + jsonFileName);
            } else {
                return null;
            }
        } catch (Exception e) {
            throw new RuntimeException("Creating Topics and Associations JSON Export failed", e);
        }
    }

    private JSONObject createTopicWithAssociationsJSON(Topic topic, List<RelatedTopic> assocTypes) throws JSONException {
        // 1) Load all childs of the topic in memory
        topic.loadChildTopics();
        // 2) Fetch all relating association of configured assocTypes
        List<Assoc> assocs = new ArrayList<Assoc>();
        for (RelatedTopic assocType : assocTypes) {
            List<RelatedTopic> relatedTopics = topic.getRelatedTopics(assocType.getUri(), null, null, null);
            for (RelatedTopic relatedTopic : relatedTopics) {
                assocs.add(relatedTopic.getRelatingAssoc().loadChildTopics());
            }
        }
        return new JSONObject()
                .put("topic", topic.toJSON())
                .put("associations", DMXUtils.toJSONArray(assocs));
    }

    @POST
    @Transactional
    @Path("/topicmap/export/json")
    public Topic exportTopicmapToJSON(@CookieParam("dmx_topicmap_id") long topicmapId) {
        try {
            log.info("Exporting Topicmap JSON ######### " + topicmapId);
            Topicmap topicmap = topicmaps.getTopicmap(topicmapId, true);
            String json = topicmap.toJSON().toString();
            InputStream in = new ByteArrayInputStream(json.getBytes("UTF-8"));
            String jsonFileName = "topicmap-" + topicmapId + ".txt";
            return file.createFile(in, file.pathPrefix() + "/" + jsonFileName);
            // return filesService.createFile(in, jsonFileName);
        } catch (Exception e) {
            throw new RuntimeException("Export failed", e);
        }
    }

    @POST
    @Path("/topicmap/export/svg")
    @Transactional
    public Topic exportTopicmapToSVG(@CookieParam("dmx_topicmap_id") long topicmapId) throws XMLStreamException {
        final int BOX_HEIGHT = 20;
        final int MARGIN_LEFT = 5;
        final int MARGIN_TOP = 14;
        final int ICON_WIDTH = 16;
        final int ICON_HEIGHT = 16;
        try {
            log.info("Exporting Topicmap SVG ######### " + topicmapId);
            // 0) Fetch topicmap data
            Topicmap topicmap = topicmaps.getTopicmap(topicmapId, true);
            Iterable<ViewTopic> topics = topicmap.getTopics();
            Iterable<ViewAssoc> associations = topicmap.getAssocs();
            // 1) Setup default file name of SVG to write to
            String svgFileName = "Exported_Topicmap_" + topicmapId + ".svg";
            // 2) Get DM4 filerepo configuration setting and write to document to root folder
            String documentPath = file.getFile("/") + "/" + svgFileName;
            // 3) Create SVGWriter
            SVGRenderer svg = new SVGRenderer(documentPath);
            svg.startGroupElement(topicmapId);
            // 4) Create all associations
            for (ViewAssoc association : associations) {
                String valueAssoc = association.getSimpleValue().toString();
                long topic1Id = association.getPlayer1().getId();
                long topic2Id = association.getPlayer2().getId();
                ViewTopic topic1 = topicmap.getTopic(topic1Id);
                int x1 = topic1.getX();
                int y1 = topic1.getY();
                ViewTopic topic2 = topicmap.getTopic(topic2Id);
                int x2 = topic2.getX();
                int y2 = topic2.getY();
                // 
                int dx = x2 - x1;
                int dy = y2 - y1;
                int label_x = dx / 2;
                int label_y = dy / 2;
                double assocLine = Math.sqrt(Math.pow(dx, 2) + Math.pow(dy, 2));
                double alpha = Math.asin(dy / assocLine) * 180 / Math.PI;
                if (dx < 0) {
                    alpha = -alpha;
                }
                svg.startGroupElement(association.getId());
                svg.line(x1, x2, y1, y2);
                svg.text(label_x, label_y, x1 + 10, y1 + 10, valueAssoc, "grey", alpha);
                svg.endElement();
            }
            // 5) Create all topics
            for (ViewTopic topic : topics) {
                String value = topic.getSimpleValue().toString();
                int x = topic.getX();
                int y = topic.getY();
                boolean visibility = topic.getVisibility();
                int boxWidth = value.length() * 9;
                if (!visibility) {
                    continue;
                }
                svg.startGroupElement(topic.getId());
                svg.rectangle(x - boxWidth / 2, y - BOX_HEIGHT / 2, boxWidth, BOX_HEIGHT, color(topic.getTypeUri()));
                svg.text(x - boxWidth / 2 + MARGIN_LEFT, y - BOX_HEIGHT / 2 + MARGIN_TOP, value, "black");
                // ### Fixme: svg.image(x + boxWidth / 2, y, ICON_WIDTH, ICON_HEIGHT, typeIconDataUri(topic.getTypeUri()));
                svg.endElement();
            }
            // 6) Close SVGWriter
            svg.endElement();
            svg.closeDocument();
            // 7) Create and return new file topic for the exported document
            return file.getFileTopic(file.pathPrefix() + "/" + svgFileName);
        } catch (Exception e) {
            throw new RuntimeException("Export Topicmap to SVG failed", e);
        }
    }

    /**
     * Understands and imports a Topicmap JSON export document created with DeepaMehta 4.x.
     * @param file A de.deepamehta.files.UploadedFile object
     * @return A JSON Object encoded as a plain text String containing two properties used by the dm4-webclient: "message" and "topic_id".
     */
    @POST
    @Path("/import")
    @Transactional
    @Consumes("multipart/form-data")
    public Topic importTopicmap(UploadedFile file) {
        try {
            String json = file.getString();

            JSONObject topicmap = new JSONObject(json);
            JSONObject info = topicmap.getJSONObject("info");

            JSONArray assocsArray = topicmap.getJSONArray("assocs");
            JSONArray topicsArray = topicmap.getJSONArray("topics");

            String origTopicmapName = info.getString("value");
            // ### Todo: Currently we import topicmaps withut their viewprops (translation)
            Topic importedTopicmap =
                    topicmaps.createTopicmap("Imported Topicmap: " + origTopicmapName
                            , "dmx.webclient.default_topicmap_renderer", null);
            long topicmapId = importedTopicmap.getId();
            log.info("###### importedTopicmapId " + topicmapId);
            // 
            Map<Long, Long> mapTopicIds = new HashMap();
            importTopics(topicsArray, mapTopicIds, topicmapId);
            importAssociations(assocsArray, mapTopicIds, topicmapId);
            return importedTopicmap;
        } catch (Exception e) {
            throw new RuntimeException("Importing Topicmap FAILED", e);
        }
    }

    /**
     * Imports a Firefox Bookmark Backup &amp; Restore JSON document generated via the Firefox Bookmark Manager. Imports bookmark folders as dmx.tags and 
     * related web resources to those tags. Tested with Mozilla Firefox 53.0.
     * @param file A de.deepamehta.files.UploadedFile object
     * @return A JSON Object encoded as a plain text String containing two properties used by the dm4-webclient: "message" and "topic_id".
     */
    @POST
    @Path("/import/bookmarks/firefox")
    @Transactional
    @Consumes("multipart/form-data")
    public String importFirefoxBookmarks(UploadedFile file) {
        try {
            String json = file.getString("UTF-8");
            JSONObject fileContent = new JSONObject(json);
            JSONArray firstChildren = fileContent.getJSONArray("children");
            log.info("###### Starting to map Firefox Bookmark Backup Entries to DeepaMehta 4 Web Resources ######");
            Topic importedNote = createNoteImportTopic(file.getName());
            int webResourcesCreatedCount = 0;
            for (int i = 0; i < firstChildren.length(); i++) {
                JSONObject entry = firstChildren.getJSONObject(i);
                if (entry.has("children")) {
                    JSONArray entryChilds = entry.getJSONArray("children");
                    for (int k = 0; k < entryChilds.length(); k++) {
                        JSONObject childEntry = entryChilds.getJSONObject(k);
                        Topic webResource = transformMozillaBookmarkEntry(childEntry, importedNote, null, 0);
                        if (webResource != null) webResourcesCreatedCount++;
                    }
                }
            }
            log.info("#### Mapping Firefox Bookmarks Backup COMPLETE: Created " + webResourcesCreatedCount + " new web resources ####");
            return "{\"message\": \"All valid bookmarks contained in the Firefox backup file were successfully mapped to "
                + "<em>Web Resources</em>.\", \"topic_id\": "+importedNote.getId()+"}";
        } catch (Exception e) {
            throw new RuntimeException("Importing Web Resources from Firefox Bookmarks Backup FAILED", e);
        }
    }

    /**
     * Understands and processes a HTML style bookmark export file generated by the Chromium Bookmark Manager.
     * Works with Chromium build number 53.0.2785.x.
     * @param file A de.deepamehta.files.UploadedFile object
     * @return A JSON Object encoded as a plain text String containing two properties used by the dm4-webclient: "message" and "topic_id".
     */
    @POST
    @Path("/import/bookmarks/chromium")
    @Transactional
    @Consumes("multipart/form-data")
    public String importChromiumBookmarks(UploadedFile file) {
        try {
            String htmlString = file.getString("UTF-8");
            Document doc = Jsoup.parse(htmlString);
            Elements folderNames = doc.getElementsByTag("dt");
            log.info("###### Starting to map Chromium Bookmark HTML Export to DeepaMehta 4 Web Resources ######");
            Topic importedNote = createNoteImportTopic(file.getName());
            log.info("### Iterating " + folderNames.size() + " chromium bookmark entries (flattened).");
            if (folderNames.size() > 0) {
                Element dummyEntry = folderNames.get(0);
                List<Element> nodes = dummyEntry.children();
                for (Element element : nodes) {
                    transformChromiumResourceEntry(importedNote, element, null);
                }
            }
            log.info("#### Mapping Chromium Bookmarks Backup to Web Resources COMPLETED ####");
            return "{\"message\": \"All valid chromium bookmark entries contained in the backup file were successfully mapped to "
                + "<em>Web Resources</em>.\", \"topic_id\": "+importedNote.getId()+"}";
        } catch (Exception e) {
            throw new RuntimeException("Importing Web Resources from Chromium Bookmarks file FAILED", e);
        }
    }

    /**
     * Understands and processes a HTML style report on a collection created with a german build of Zotero 4.0.29.18.
     * @param file A de.deepamehta.files.UploadedFile object
     * @return A JSON Object encoded as a plain text String containing two properties used by the dm4-webclient: "message" and "topic_id".
     */
    @POST
    @Path("/import/bookmarks/zotero-report")
    @Transactional
    @Consumes("multipart/form-data")
    public String importZoteroReportBookmarks(UploadedFile file) {
        try {
            String htmlString = file.getString("UTF-8");
            Document doc = Jsoup.parse(htmlString);
            Elements webpages = doc.getElementsByClass("webpage");
            log.info("###### Starting to map Zotero Report Bookmarks to DeepaMehta 4 Web Resources ######");
            Topic importedNote = createNoteImportTopic(file.getName());
            log.info("### Iterating " + webpages.size() + " webpages in zotero report.");
            for (Element webpage : webpages) {
                transformZoteroWebpageEntry(importedNote, webpage);
            }
            log.info("#### Mapping Zotero Report Bookmarks to Web Resources COMPLETED ####");
            return "{\"message\": \"All valid webpages in the zotero report file were successfully mapped to "
                + "<em>Web Resources</em>.\", \"topic_id\": "+importedNote.getId()+"}";
        } catch (Exception e) {
            throw new RuntimeException("Importing Web Resources from Zotero Report Bookmarks file FAILED", e);
        }
    }

    // --- Private Importer Transformation Methods ---

    private Topic transformMozillaBookmarkEntry(JSONObject childEntry, Topic importedNote, Topic folderNameTag, int levelCount) {
        Topic webResource = null;
        int recursionCount = levelCount;
        try {
            String entryType = childEntry.getString("type");
            if (entryType.equals("text/x-moz-place")) {
                // Check if (folderNameTag != null), associate item with tag too
                if (childEntry.has("title") && childEntry.has("uri")) {
                    webResource = transformFirefoxResourceEntry(childEntry);
                    if (webResource != null) { // Topic was either fetched or newly created succcesfully
                        createBookmarkRelations(importedNote, webResource, folderNameTag);
                    } else {
                        // An exception has occured.
                        log.warning("Web Resource Entry could not be created with JSONObject: " + childEntry.toString());
                    }
                } else {
                    log.warning("Skipping Bookmark entry due to missing Title or URL, " + childEntry.toString());
                }
            } else if (entryType.equals("text/x-moz-place-container")) {
                log.warning("Bookmarking Container Detected - Mapping Bookmarker Folders to Tags...");
                // 1) Get or create folderName tag topic
                String folderName = childEntry.getString("title");
                // ### Fixme: Support for caseInsensitive tag matching
                Topic folderTopic = createTagTopic(folderName);
                if (folderNameTag != null) {
                    getOrCreateSimpleAssoc(folderNameTag, folderTopic);
                }
                JSONArray entryChildsChilds = childEntry.getJSONArray("children");
                log.info("  "+recursionCount+ "ndLevel Bookmark Folder " + folderName + " - TODO: Transform \""+folderName+"\" into TAG");
                // 2.0) If entry is of type bookmark, create a tag for it, associate it with the parent tag and then
                    // go over all its children and (recursively) call transformMozillaBookmarkEntry on them
                for (int m = 0; m < entryChildsChilds.length(); m++) {
                    recursionCount++;
                    JSONObject childChildEntry = entryChildsChilds.getJSONObject(m);
                    transformMozillaBookmarkEntry(childChildEntry, importedNote, folderTopic, recursionCount);
                }
            }
        } catch (JSONException ex) {
            Logger.getLogger(ImportExportPlugin.class.getName()).log(Level.SEVERE, null, ex);
        }
        return webResource;
    }

    private Topic createTagTopic(String tagName) {
        return dmx.createTopic(mf.newTopicModel("dmx.tags.tag",
                mf.newChildTopicsModel().put("dmx.tags.tag_name", tagName)));
    }
    
    private Topic transformFirefoxResourceEntry(JSONObject childEntry) {
        try {
            String bookmarkDescription = childEntry.getString("title");
            String bookmarkUrl = childEntry.getString("uri");
            long dateAdded = 0;
            if (bookmarkUrl.startsWith("place:") || bookmarkUrl.startsWith("chrome:")
                || bookmarkUrl.startsWith("about:")) return null; // do not import browser internal bookmarks
            if (childEntry.has("dateAdded")) {
                dateAdded = childEntry.getLong("dateAdded");
                dateAdded = new Date(dateAdded / 1000).getTime();
            } else {
                log.warning("Could not detect " + bookmarkDescription + " dateAdded timestamp, setting it NOW, DEBUG: " + childEntry.toString());
            }
            long lastModified = 0;
            if (childEntry.has("lastModified")) {
                childEntry.getLong("lastModified");
                lastModified = new Date(lastModified / 1000).getTime();
            } else {
                log.warning("Could not detect " + bookmarkDescription + " lastModified timestamp, setting it NOW, DEBUG: " + childEntry.toString());
            }
            return getOrCreateWebResource(bookmarkUrl, bookmarkDescription, dateAdded, lastModified);
        } catch (JSONException ex) {
            Logger.getLogger(ImportExportPlugin.class.getName()).log(Level.SEVERE, null, ex);
            return null;
        }
    }

    private Topic getTagTopic(String name) {
        Topic tagName = dmx.getTopicByValue("dmx.tags.tag_name", new SimpleValue(name));
        return getTagTopic(tagName);
    }

    private Topic getTagTopic(Topic name) {
        return name.getRelatedTopic(null, "dmx.core.child", "dmx.core.parent", "dmx.tags.tag");
    }
    
    private void transformChromiumResourceEntry(Topic importedNote, Element element, Topic toBeRelated) {
        if (element.nodeName().equals("a")) {
            String linkHref = element.attr("href");
            String linkName = element.text();
            String linkAddedValue = element.attr("add_date");
            String linkModifiedValue = element.attr("last_modified");
            long linkAdded = new Date().getTime();
            long linkModified = new Date().getTime();
            if (!linkAddedValue.isEmpty()) {
                linkAdded = Long.parseLong(linkAddedValue)*1000;
            }
            if (!linkModifiedValue.isEmpty()) {
                linkModified = Long.parseLong(linkModifiedValue)*1000;
            }
            /* String associatedWithMessage = (toBeRelated != null) ? ", Associate with : " + toBeRelated.getSimpleValue() + "" : "";
            log.info("### Processing chromium link entry  \"" + linkName + "\" (" + linkHref + "), Added: "
                + new Date(linkAdded*1000).toLocaleString() + associatedWithMessage); **/
            Topic webResource = getOrCreateWebResource(linkHref, linkName, (linkAdded*1000), linkModified);
            createBookmarkRelations(importedNote, webResource, toBeRelated);
        } else if (element.nodeName().equals("h3")) {
            String text = element.ownText().trim();
            long folderAdded = Long.parseLong(element.attr("add_date"));
            String linkModifiedValue = element.attr("last_modified");
            long folderModified = new Date().getTime();
            if (!linkModifiedValue.isEmpty()) {
                folderModified = Long.parseLong(linkModifiedValue);
            }
            log.info("### Processing chromium bookmark folder element named \"" + text + "\"");
            Topic tag = getTagTopic(text);
            if (tag == null) {
                tag = createTagTopic(text);
                tag.setProperty(DM4_TIME_CREATED, folderAdded, true);
                if (folderModified != 0) {
                    tag.setProperty(DM4_TIME_MODIFIED, folderModified, true);
                }
                log.info("NEW tag \""+text+"\" created during import");
            }
            // mirror bookmark folder hierarchy through a simply tag to tag association
            if (toBeRelated != null) {
                getOrCreateSimpleAssoc(toBeRelated, tag);
            }
            // associate tag with imported note
            if (importedNote != null && tag != null) {
                getOrCreateSimpleAssoc(importedNote, tag);
            }
            transformChromiumResourceEntry(importedNote, element.nextElementSibling(), tag);
        } else if (element.nodeName().equals("dt") || element.nodeName().equals("dl")) {
            List<Element> childNodes = element.children();
            log.info("### Processing chromium list element with name " + element.nodeName() + " and "+childNodes.size()+" childs");
            for (Element childNode : childNodes) {
                transformChromiumResourceEntry(importedNote, childNode, toBeRelated);
            }
        }
    }

    private Topic transformZoteroWebpageEntry(Topic importedNote, Element listItem) {
        // 0) Declare parsers goal
        String webpageTitle = null, webpageUrl = null, entryType = null;
        long bookmarkCreated = new Date().getTime();
        long bookmarkModified = new Date().getTime();
        Topic webpage = null;
        // 1) Collect Entry Name
        List<Element> childs = listItem.children();
        for (Element element : childs) {
            if (element.nodeName().equals("h2")) {
                String text = element.ownText().trim();
                webpageTitle = text;
                log.info("### Processed zotero report webpage title \"" + text + "\"");
            }
        }
        // 2) Collect tags of an entry
        List<String> bookmarkTags = new ArrayList<String>();
        List<Element> tagElements = listItem.getElementsByClass("tags");
        for (Element tagElement : tagElements) {
            if (tagElement.nodeName().equals("ul")) {
                List<Element> tagEntries = tagElement.getElementsByTag("li");
                for (Element tagEntry : tagEntries) {
                    bookmarkTags.add(tagEntry.ownText().trim());
                }
            }
        }
        // 3) Collect attribute values of an entry
        List<Element> attributes = listItem.getElementsByTag("tr");
        for (Element attribute : attributes) { // each tr stands for an attribute and has exactly 1 th and 1 td
            Element keyCell = attribute.child(0); // th
            Element valueCell = attribute.child(1); // td
            String keyOne = keyCell.ownText().trim();
            if (keyOne.equals(ZOTERO_ENTRY_URL_COLUMN_KEY)) {
                List<Element> ahrefs = valueCell.getElementsByTag("a");
                if (ahrefs.size() > 0) {
                    webpageUrl = ahrefs.get(0).attr("href");
                    log.fine("### Parsed zotero report webpage URL: " + webpageUrl);
                }
            } else if (keyOne.equals(ZOTERO_ENTRY_TYPE_COLUMN_KEY)) {
                entryType = valueCell.ownText();
                // log.info("### Skipped zotero report entry of type: \"" + entryType + "\"");
            } else if (keyOne.equals(ZOTERO_ADDED_AT_COLUMN_KEY)) {
                String entryAdded = valueCell.ownText();
                if (!entryAdded.isEmpty()) {
                    try {
                        bookmarkCreated = new Date().parse(entryAdded);
                    } catch (IllegalArgumentException iex) {
                        log.warning("Could not parse date of bookmark created \"" + entryAdded + "\", cause " + iex.getMessage());
                    }
                }
            } else if (keyOne.equals(ZOTERO_MODIFIED_AT_COLUMN_KEY)) {
                String entryModified = valueCell.ownText();
                if (!entryModified.isEmpty()) {
                    try {
                        bookmarkModified = new Date().parse(entryModified);
                    } catch (IllegalArgumentException iex) {
                        log.warning("Could not parse date of bookmark last modified \"" + entryModified + "\", cause " + iex.getMessage());
                    }
                }
            }
        }
        // We import anything that has _some value_ in the URL column
        if (webpageUrl != null) {
            webpage = getOrCreateWebResource(webpageUrl, webpageTitle + "<br/>" + entryType, bookmarkCreated, bookmarkModified);
            if (bookmarkTags.size() > 0) {
                log.fine("### Importing " + bookmarkTags + " as tags for this webpage from zotero report");
            }
            for (String tagValue : bookmarkTags) {
                Topic tag = getTagTopic(tagValue);
                if (tag == null) {
                    tag = createTagTopic(tagValue);
                    log.info("NEW tag \""+tagValue+"\" created during import");
                }
                if (tag != null) {
                    createBookmarkRelations(importedNote, webpage, tag);
                }
            }
        }
        return webpage;
    }

    // --- Private Utilty Methods ---

    private Topic createNoteImportTopic(String fileName) {
        ChildTopicsModel childValues = mf.newChildTopicsModel();
        childValues.put("dmx.notes.title", "Browser Bookmarks Import, " + fileName);
        childValues.put("dmx.notes.text", "This note relates web resources created through an import process, namely the Firefox Bookmark Backup File "
            + "(" + fileName +"). Please do not delete this note as it might become helpful if you need to identify which "
            + "web resources you imported when and from which backup file they originated from.");
        Topic importerNote = dmx.createTopic(mf.newTopicModel("dmx.notes.note", childValues));
        log.info("### Importer Note Topic for \""+fileName+"\" CREATED");
        return importerNote;
    }

    private Assoc createBookmarkRelations(Topic importerNote, Topic webResource, Topic folderNameTag) {
        Assoc importedAssoc = null;
        if (importerNote != null) {
            // 1) Check if association to "importerNote" exists
            importedAssoc = dmx.getAssoc("dmx.core.association", importerNote.getId(), webResource.getId(),
                "dmx.core.default", "dmx.core.default");
            if (importedAssoc == null) {
                // 2) Create association to "importerNote" exists
                importedAssoc = dmx.createAssoc(mf.newAssocModel("dmx.core.association",
                    mf.newTopicPlayerModel(importerNote.getId(), "dmx.core.default"),
                    mf.newTopicPlayerModel(webResource.getId(), "dmx.core.default")));
            }
        }
        if (folderNameTag != null) {
            getOrCreateSimpleAssoc(folderNameTag, webResource); // choosing to set the tag as parent
        }
        return importedAssoc;
    }

    private Assoc getOrCreateSimpleAssoc(Topic defaultPlayer1, Topic defaultPlayer2) {
        // 3) Check and create assoc to folderNameTag
        Assoc folderTagAssoc = dmx.getAssoc("dmx.core.association", defaultPlayer1.getId(), defaultPlayer2.getId(),
            "dmx.core.parent", "dmx.core.child");
        if (folderTagAssoc == null) {
            // 4) Create assoc from webResource to folderNameTag
            folderTagAssoc = dmx.createAssoc(mf.newAssocModel("dmx.core.association",
                mf.newTopicPlayerModel(defaultPlayer1.getId(), "dmx.core.parent"),
                mf.newTopicPlayerModel(defaultPlayer2.getId(), "dmx.core.child")));
            log.info("NEW relation from \"" + defaultPlayer1.getTypeUri() + "\" created to \"" + defaultPlayer2.getTypeUri()+ "\"");
        }
        return folderTagAssoc;
    }

    private Topic getOrCreateWebResource(String url, String description, long created, long modified) {
        // 1) Check if a Web Resource Topic with that URL already exists
        Topic webResource;
        try {
            webResource = dmx.getTopicByValue("dmx.webbrowser.url", new SimpleValue(url.trim()));
            if (webResource != null) {
                log.info("### Web Resource \""+url+"\" EXISTS - NOT UPDATED");
                Topic webRsrcParent = webResource.getRelatedTopic("dmx.core.composition", "dmx.core.child", "dmx.core.parent",
                    "dmx.webbrowser.web_resource");
                return (webRsrcParent != null) ? webRsrcParent : webResource;
            }
        } catch (RuntimeException re) {
            // This could be an AccessControlExcception or a runtime exception pointing at ambiguity of a
            // java.util.NoSuchElementException: More than one element in org.neo4j.index.impl.lucene.LuceneIndex
            // In any way, we cannot do any thing more about the circumstances which lead us here but noting them.
            log.warning("Web Resource could not be created, either due to an access control issue or a "
                + "messed up lucene KEY index (allowing web resources to exists just once in a DB), caused by: "
                + re.getLocalizedMessage());
        }
        // 2) Create new Web Resource Topic
        ChildTopicsModel childValues = mf.newChildTopicsModel();
        childValues.put("dmx.webbrowser.url", url.trim());
        childValues.put("dmx.webbrowser.web_resource_description", description);
        webResource = dmx.createTopic(mf.newTopicModel("dmx.webbrowser.web_resource", childValues));
        if (created != 0) webResource.setProperty(DM4_TIME_CREATED, created, true);
        // lastModified is anyway overwritten by dm4-times plugin as (i guess) setting the timepropery is an udpate
        if (modified != 0) webResource.setProperty("dmx.time.modified", modified, true);
        log.info("### Web Resource \""+url+"\" CREATED");
        return webResource;
    }

    // Import topics
    private void importTopics(JSONArray topicsArray, Map<Long, Long> mapTopicIds, long topicmapId) {
        for (int i = 0, size = topicsArray.length(); i < size; i++) {
            try {
                JSONObject topic = topicsArray.getJSONObject(i);
                createTopic(topic, mapTopicIds, topicmapId);
            } catch (Exception e) {
                log.warning("Topic NOT imported!!" + e);
            }
        }
    }

    // Import associations
    private void importAssociations(JSONArray assocsArray, Map<Long, Long> mapTopicIds, long topicmapId) {
        for (int i = 0, size = assocsArray.length(); i < size; i++) {
            try {
                JSONObject association = assocsArray.getJSONObject(i);
                createAssociation(association, mapTopicIds, topicmapId);
            } catch (Exception e) {
                log.warning("Assoc NOT imported");
            }
        }
    }

    private String color(String typeUri) {
        if (typeUri.equals("dmx.contacts.institution")) {
            return "lightblue";
        } else if (typeUri.equals("dmx.contacts.person")) {
            return "lightblue";
        } else if (typeUri.equals("dmx.notes.note")) {
            return "lightblue";
        } else {
            return "lightblue";
        }
    }

    /** ### Make this work for custom icons too, this works currently just with icons included in the standard
     * distribution. */
    private String typeIconDataUri(String typeUri) throws IOException {
        TopicType topicType = dmx.getTopicType(typeUri);
        String iconPath = (String) topicType.getViewConfigValue("dmx.webclient.view_config", "dmx.webclient.icon");
        InputStream iconIS = null;
        // TODO: Load icons bundled in other plugins
        // String pluginPath = iconPath.substring(1, sep);
        // Plugin plugin = dmx.getPlugin(pluginPath);
        try {
            int sep = iconPath.indexOf("/", 2); // Note: iconPath may be null and throw a NPE
            String imagePath = "web" + iconPath.substring(sep);
            iconIS = getStaticResource(imagePath);
            log.fine("##### IconIS " + iconIS);
        } catch (Exception e) {
            // Icon resource not found in this plugin
            log.info("### FALLBACK to standard grey icon as typeIcon for \""
                    + typeUri + "\" icon could not be determined " + "during SVG Export");
            iconIS = dmx.getPlugin("systems.dmx.webclient").getStaticResource("web/images/ball-gray.png");
        }
        // create base64 representation of the current type icon
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count = 0;
        while ((count = iconIS.read(buffer)) != -1) {
            baos.write(buffer, 0, count);
        }
        byte[] fileContent = baos.toByteArray();
        // all chars in encoded are guaranteed to be 7-bit ASCII
        byte[] encoded = Base64.encode(fileContent);
        String imgBase64Str = new String(encoded);
        log.fine("##### IMG BASE64 " + imgBase64Str);
        //
        return "data:image/png;base64," + imgBase64Str;
    }

    private void createTopic(JSONObject topic, Map<Long, Long> mapTopicIds, long topicmapId) throws JSONException {
        TopicModel model = mf.newTopicModel(topic);
        ViewProps viewProps = mf.newViewProps(topic.getJSONObject("view_props"));
        // maybe replace "dm4" prefixes
        long origTopicId = model.getId();
        Topic newTopic = dmx.createTopic(model);
        long topicId = newTopic.getId();
        mapTopicIds.put(origTopicId, topicId);
        topicmaps.addTopicToTopicmap(topicmapId, topicId, viewProps);
    }

    private void createAssociation(JSONObject association, Map<Long, Long> mapTopicIds, long topicmapId) {
        // log.info("Assoc import " + association.toJson());
        AssocModel assocModel = mf.newAssocModel(association);
        PlayerModel player1 = assocModel.getPlayer1();
        // ### Fixme: player1.setPlayerId(mapTopicIds.get(player1.getId()));
        PlayerModel player2 = assocModel.getPlayer2();
        // ### Fixme: player2.setPlayerId(mapTopicIds.get(player2.getId()));
        Assoc newAssociation = dmx.createAssoc(assocModel);
        long assocId = newAssociation.getId();
        topicmaps.addAssocToTopicmap(topicmapId, assocId, mf.newViewProps(true));
    }

}
