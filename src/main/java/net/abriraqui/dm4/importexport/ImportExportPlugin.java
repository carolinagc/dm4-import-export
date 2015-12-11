package net.abriraqui.dm4.importexport;

import com.sun.jersey.core.util.Base64;
import de.deepamehta.core.Association;
import de.deepamehta.core.Topic;
import de.deepamehta.core.TopicType;
import de.deepamehta.core.model.AssociationModel;
import de.deepamehta.core.model.RoleModel;
import de.deepamehta.core.model.TopicModel;
import de.deepamehta.core.osgi.PluginActivator;
import de.deepamehta.core.service.Inject;
import de.deepamehta.core.service.Transactional;
import de.deepamehta.plugins.files.FilesPlugin;
import de.deepamehta.plugins.files.FilesService;
import de.deepamehta.plugins.files.UploadedFile;
import de.deepamehta.plugins.topicmaps.TopicmapsService;
import de.deepamehta.plugins.topicmaps.model.AssociationViewmodel;
import de.deepamehta.plugins.topicmaps.model.TopicViewmodel;
import de.deepamehta.plugins.topicmaps.model.TopicmapViewmodel;
import de.deepamehta.plugins.topicmaps.model.ViewProperties;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import javax.ws.rs.*;
import javax.xml.stream.XMLStreamException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

@Path("/import-export")
@Produces("application/json")
public class ImportExportPlugin extends PluginActivator {

    @Inject
    private TopicmapsService topicmapsService;
    @Inject
    private FilesService filesService;

    private Logger log = Logger.getLogger(getClass().getName());

    // Service implementation //

    @POST
    @Transactional
    @Path("/export/json")
    public Topic exportTopicmapToJSON(@CookieParam("dm4_topicmap_id") long topicmapId) {
        try {
            log.info("Exporting Topicmap JSON ######### " + topicmapId);
            TopicmapViewmodel topicmap = topicmapsService.getTopicmap(topicmapId, true);
            String json = topicmap.toJSON().toString();
            InputStream in = new ByteArrayInputStream(json.getBytes("UTF-8"));
            String jsonFileName = "topicmap-" + topicmapId + ".txt";
            return filesService.createFile(in, prefix() + "/" + jsonFileName);
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
            TopicmapViewmodel topicmap = topicmapsService.getTopicmap(topicmapId, true);
            Iterable<TopicViewmodel> topics = topicmap.getTopics();
            Iterable<AssociationViewmodel> associations = topicmap.getAssociations();
            // 1) Setup default file name of SVG to write to
            String svgFileName = "Exported_Topicmap_" + topicmapId + ".svg";
            // 2) Get DM4 filerepo configuration setting and write to document to root folder
            String documentPath = filesService.getFile("/") + "/" + svgFileName;
            // 3) Create SVGWriter
            SVGRenderer svg = new SVGRenderer(documentPath);
            svg.startGroupElement(topicmapId);
            // 4) Create all associations
            for (AssociationViewmodel association : associations) {
                String valueAssoc = association.getSimpleValue().toString();
                long topic1Id = association.getRoleModel1().getPlayerId();
                long topic2Id = association.getRoleModel2().getPlayerId();
                TopicViewmodel topic1 = topicmap.getTopic(topic1Id);
                int x1 = topic1.getX();
                int y1 = topic1.getY();
                TopicViewmodel topic2 = topicmap.getTopic(topic2Id);
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
            for (TopicViewmodel topic : topics) {
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
            return filesService.getFileTopic(prefix() + "/" + svgFileName);
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
                    topicmapsService.createTopicmap("Imported Topicmap: " + origTopicmapName
                            , "dm4.webclient.default_topicmap_renderer");
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
        TopicType topicType = dms.getTopicType(typeUri);
        String iconPath = (String) topicType.getViewConfig("dm4.webclient.view_config", "dm4.webclient.icon");
        InputStream iconIS = null;
        // TODO: Load icons bundled in other plugins
        // String pluginPath = iconPath.substring(1, sep);
        // Plugin plugin = dms.getPlugin(pluginPath);
        try {
            int sep = iconPath.indexOf("/", 2); // Note: iconPath may be null and throw a NPE
            String imagePath = "web" + iconPath.substring(sep);
            iconIS = getStaticResource(imagePath);
            log.fine("##### IconIS " + iconIS);
        } catch (Exception e) {
            // Icon resource not found in this plugin
            log.info("### FALLBACK to standard grey icon as typeIcon for \""
                    + typeUri + "\" icon could not be determined " + "during SVG Export");
            iconIS = dms.getPlugin("de.deepamehta.webclient").getStaticResource("web/images/ball-gray.png");
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
        TopicModel model = new TopicModel(topic);
        ViewProperties viewProps = new ViewProperties(topic.getJSONObject("view_props"));
        long origTopicId = model.getId();
        Topic newTopic = dms.createTopic(model);
        long topicId = newTopic.getId();
        mapTopicIds.put(origTopicId, topicId);
        topicmapsService.addTopicToTopicmap(topicmapId, topicId, viewProps);
    }

    private void createAssociation(JSONObject association, Map<Long, Long> mapTopicIds, long topicmapId) {
        AssociationModel assocModel = new AssociationModel(association);
        RoleModel role1 = assocModel.getRoleModel1();
        role1.setPlayerId(mapTopicIds.get(role1.getPlayerId()));
        RoleModel role2 = assocModel.getRoleModel2();
        role2.setPlayerId(mapTopicIds.get(role2.getPlayerId()));
        Association newAssociation = dms.createAssociation(assocModel);
        long assocId = newAssociation.getId();
        topicmapsService.addAssociationToTopicmap(topicmapId, assocId);
    }

    private String prefix() {
        File repo = filesService.getFile("/");
        return ((FilesPlugin) filesService).repoPath(repo);
    }

}
