package net.abriraqui.dm4.importexport;

import com.sun.jersey.core.util.Base64;
import de.deepamehta.core.Association;
import de.deepamehta.core.Topic;
import de.deepamehta.core.TopicType;
import de.deepamehta.core.model.AssociationModel;
import de.deepamehta.core.model.ChildTopicsModel;
import de.deepamehta.core.model.RoleModel;
import de.deepamehta.core.model.SimpleValue;
import de.deepamehta.core.model.TopicModel;
import de.deepamehta.core.model.topicmaps.AssociationViewModel;
import de.deepamehta.core.model.topicmaps.TopicViewModel;
import de.deepamehta.core.model.topicmaps.ViewProperties;
import de.deepamehta.core.osgi.PluginActivator;
import de.deepamehta.core.service.Inject;
import de.deepamehta.core.service.Transactional;
import de.deepamehta.files.FilesService;
import de.deepamehta.files.UploadedFile;
import de.deepamehta.tags.TaggingService;
import de.deepamehta.topicmaps.TopicmapsService;
import de.deepamehta.topicmaps.model.TopicmapViewmodel;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import javax.ws.rs.*;
import javax.xml.stream.XMLStreamException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

@Path("/import-export")
@Produces("application/json")
public class ImportExportPlugin extends PluginActivator {

    @Inject
    private TopicmapsService topicmaps;
    @Inject
    private FilesService file;
    @Inject
    private TaggingService tagging;

    private Logger log = Logger.getLogger(getClass().getName());

    private static final String DM4_TIME_CREATED = "dm4.time.created";
    private static final String DM4_TIME_MODIFIED = "dm4.time.modified";

    // Service implementation //

    @POST
    @Transactional
    @Path("/export/json")
    public Topic exportTopicmapToJSON(@CookieParam("dm4_topicmap_id") long topicmapId) {
        try {
            log.info("Exporting Topicmap JSON ######### " + topicmapId);
            TopicmapViewmodel topicmap = topicmaps.getTopicmap(topicmapId, true);
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
    @Path("/export/svg")
    @Transactional
    public Topic exportTopicmapToSVG(@CookieParam("dm4_topicmap_id") long topicmapId) throws XMLStreamException {
        final int BOX_HEIGHT = 20;
        final int MARGIN_LEFT = 5;
        final int MARGIN_TOP = 14;
        final int ICON_WIDTH = 16;
        final int ICON_HEIGHT = 16;
        try {
            log.info("Exporting Topicmap SVG ######### " + topicmapId);
            // 0) Fetch topicmap data
            TopicmapViewmodel topicmap = topicmaps.getTopicmap(topicmapId, true);
            Iterable<TopicViewModel> topics = topicmap.getTopics();
            Iterable<AssociationViewModel> associations = topicmap.getAssociations();
            // 1) Setup default file name of SVG to write to
            String svgFileName = "Exported_Topicmap_" + topicmapId + ".svg";
            // 2) Get DM4 filerepo configuration setting and write to document to root folder
            String documentPath = file.getFile("/") + "/" + svgFileName;
            // 3) Create SVGWriter
            SVGRenderer svg = new SVGRenderer(documentPath);
            svg.startGroupElement(topicmapId);
            // 4) Create all associations
            for (AssociationViewModel association : associations) {
                String valueAssoc = association.getSimpleValue().toString();
                long topic1Id = association.getRoleModel1().getPlayerId();
                long topic2Id = association.getRoleModel2().getPlayerId();
                TopicViewModel topic1 = topicmap.getTopic(topic1Id);
                int x1 = topic1.getX();
                int y1 = topic1.getY();
                TopicViewModel topic2 = topicmap.getTopic(topic2Id);
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
            for (TopicViewModel topic : topics) {
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
                svg.image(x + boxWidth / 2, y, ICON_WIDTH, ICON_HEIGHT, typeIconDataUri(topic.getTypeUri()));
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
            Topic importedTopicmap =
                    topicmaps.createTopicmap("Imported Topicmap: " + origTopicmapName
                            , "dm4.webclient.default_topicmap_renderer", false);
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
            throw new RuntimeException("Importing Firefox Bookmarks FAILED", e);
        }
    }

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
            throw new RuntimeException("Importing Chromium Bookmarks FAILED", e);
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
                Topic folderTopic = tagging.getTagTopic(folderName, true);
                if (folderTopic == null) {
                    // 1.1) Create new Tag Item
                    folderTopic = tagging.createTagTopic(folderName, "Imported Firefox Bookmarks Folder Name", true);
                    if (folderNameTag != null) {
                        // 1.2) Create association between current tag and parent tag (if given)
                        getOrCreateSimpleAssoc(folderNameTag, folderTopic);
                    }
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
            Topic tag = tagging.getTagTopic(text, true);
            if (tag == null) {
                tag = tagging.createTagTopic(text, "Imported Chromium Bookmark Folder Name", false);
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

    // --- Private Utilty Methods ---

    private Topic createNoteImportTopic(String fileName) {
        ChildTopicsModel childValues = mf.newChildTopicsModel();
        childValues.put("dm4.notes.title", "Browser Bookmarks Import, " + fileName);
        childValues.put("dm4.notes.text", "This note relates web resources created through an import process, namely the Firefox Bookmark Backup File "
            + "(" + fileName +"). Please do not delete this note as it might become helpful if you need to identify which "
            + "web resources you imported when and from which backup file they originated from.");
        Topic importerNote = dm4.createTopic(mf.newTopicModel("dm4.notes.note", childValues));
        log.info("### Importer Note Topic for \""+fileName+"\" CREATED");
        return importerNote;
    }

    private Association createBookmarkRelations(Topic importerNote, Topic webResource, Topic folderNameTag) {
        Association importedAssoc = null;
        if (importerNote != null) {
            // 1) Check if association to "importerNote" exists
            importedAssoc = dm4.getAssociation("dm4.core.association", importerNote.getId(), webResource.getId(),
                "dm4.core.default", "dm4.core.default");
            if (importedAssoc == null) {
                // 2) Create association to "importerNote" exists
                importedAssoc = dm4.createAssociation(mf.newAssociationModel("dm4.core.association",
                    mf.newTopicRoleModel(importerNote.getId(), "dm4.core.default"),
                    mf.newTopicRoleModel(webResource.getId(), "dm4.core.default")));
            }
        }
        if (folderNameTag != null) {
            getOrCreateSimpleAssoc(folderNameTag, webResource); // choosing to set the tag as parent
        }
        return importedAssoc;
    }

    private Association getOrCreateSimpleAssoc(Topic defaultPlayer1, Topic defaultPlayer2) {
        // 3) Check and create assoc to folderNameTag
        Association folderTagAssoc = dm4.getAssociation("dm4.core.association", defaultPlayer1.getId(), defaultPlayer2.getId(),
            "dm4.core.parent", "dm4.core.child");
        if (folderTagAssoc == null) {
            // 4) Create assoc from webResource to folderNameTag
            folderTagAssoc = dm4.createAssociation(mf.newAssociationModel("dm4.core.association",
                mf.newTopicRoleModel(defaultPlayer1.getId(), "dm4.core.parent"),
                mf.newTopicRoleModel(defaultPlayer2.getId(), "dm4.core.child")));
            log.info("NEW relation from \"" + defaultPlayer1.getTypeUri() + "\" created to \"" + defaultPlayer2.getTypeUri()+ "\"");
        }
        return folderTagAssoc;
    }

    private Topic getOrCreateWebResource(String url, String description, long created, long modified) {
        // 1) Check if a Web Resource Topic with that URL already exists
        Topic webResource;
        try {
            webResource = dm4.getTopicByValue("dm4.webbrowser.url", new SimpleValue(url));
            if (webResource != null) {
                log.info("### Web Resource \""+url+"\" EXISTS - NOT UPDATED");
                Topic webRsrcParent = webResource.getRelatedTopic("dm4.core.composition", "dm4.core.child", "dm4.core.parent",
                    "dm4.webbrowser.web_resource");
                return (webRsrcParent != null) ? webRsrcParent : webResource;
            }
        } catch (RuntimeException re) {
            // This could be an AccessControlExcception or a runtime exception pointing at ambiguity of a
            // java.util.NoSuchElementException: More than one element in org.neo4j.index.impl.lucene.LuceneIndex
            // In any way, we cannot do any thing more about the circumstances which lead us here but noting them.
            log.warning("Web Resource could not be created, either due to an access control issue or a "
                + "messed up lucene KEY index (allowing web resources to exists just once in a DB), caused by: "
                + re.getCause().getLocalizedMessage());
        }
        // 2) Create new Web Resource Topic
        ChildTopicsModel childValues = mf.newChildTopicsModel();
        childValues.put("dm4.webbrowser.url", url);
        childValues.put("dm4.webbrowser.web_resource_description", description);
        webResource = dm4.createTopic(mf.newTopicModel("dm4.webbrowser.web_resource", childValues));
        if (created != 0) webResource.setProperty(DM4_TIME_CREATED, created, true);
        // lastModified is anyway overwritten by dm4-times plugin as (i guess) setting the timepropery is an udpate
        if (modified != 0) webResource.setProperty("dm4.time.modified", modified, true);
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
                log.warning("Association NOT imported");
            }
        }
    }

    private String color(String typeUri) {
        if (typeUri.equals("dm4.contacts.institution")) {
            return "lightblue";
        } else if (typeUri.equals("dm4.contacts.person")) {
            return "lightblue";
        } else if (typeUri.equals("dm4.notes.note")) {
            return "lightblue";
        } else {
            return "lightblue";
        }
    }

    /** ### Make this work for custom icons too, this works currently just with icons included in the standard
     * distribution. */
    private String typeIconDataUri(String typeUri) throws IOException {
        TopicType topicType = dm4.getTopicType(typeUri);
        String iconPath = (String) topicType.getViewConfig("dm4.webclient.view_config", "dm4.webclient.icon");
        InputStream iconIS = null;
        // TODO: Load icons bundled in other plugins
        // String pluginPath = iconPath.substring(1, sep);
        // Plugin plugin = dm4.getPlugin(pluginPath);
        try {
            int sep = iconPath.indexOf("/", 2); // Note: iconPath may be null and throw a NPE
            String imagePath = "web" + iconPath.substring(sep);
            iconIS = getStaticResource(imagePath);
            log.fine("##### IconIS " + iconIS);
        } catch (Exception e) {
            // Icon resource not found in this plugin
            log.info("### FALLBACK to standard grey icon as typeIcon for \""
                    + typeUri + "\" icon could not be determined " + "during SVG Export");
            iconIS = dm4.getPlugin("de.deepamehta.webclient").getStaticResource("web/images/ball-gray.png");
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
        ViewProperties viewProps = new ViewProperties(topic.getJSONObject("view_props"));
        long origTopicId = model.getId();
        Topic newTopic = dm4.createTopic(model);
        long topicId = newTopic.getId();
        mapTopicIds.put(origTopicId, topicId);
        topicmaps.addTopicToTopicmap(topicmapId, topicId, viewProps);
    }

    private void createAssociation(JSONObject association, Map<Long, Long> mapTopicIds, long topicmapId) {
        AssociationModel assocModel = mf.newAssociationModel(association);
        RoleModel role1 = assocModel.getRoleModel1();
        role1.setPlayerId(mapTopicIds.get(role1.getPlayerId()));
        RoleModel role2 = assocModel.getRoleModel2();
        role2.setPlayerId(mapTopicIds.get(role2.getPlayerId()));
        Association newAssociation = dm4.createAssociation(assocModel);
        long assocId = newAssociation.getId();
        topicmaps.addAssociationToTopicmap(topicmapId, assocId);
    }

}
