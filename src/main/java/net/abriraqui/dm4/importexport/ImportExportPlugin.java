package net.abriraqui.dm4.importexport;

import de.deepamehta.core.osgi.PluginActivator;
import de.deepamehta.core.util.JavaUtils;
import de.deepamehta.core.util.DeepaMehtaUtils;
import de.deepamehta.core.service.PluginService;
import de.deepamehta.core.service.Plugin;
import de.deepamehta.core.service.annotation.ConsumesService;
import de.deepamehta.core.Topic;
import de.deepamehta.core.TopicType;
import de.deepamehta.core.ViewConfiguration;
import de.deepamehta.core.Association;
import de.deepamehta.core.model.TopicModel;
import de.deepamehta.core.model.CompositeValueModel;
import de.deepamehta.core.model.AssociationModel;
import de.deepamehta.core.model.RoleModel;
import de.deepamehta.core.model.SimpleValue;
import de.deepamehta.core.storage.spi.DeepaMehtaTransaction;

import de.deepamehta.plugins.topicmaps.service.TopicmapsService;
import de.deepamehta.plugins.topicmaps.model.TopicmapViewmodel;
import de.deepamehta.plugins.topicmaps.model.TopicViewmodel;
import de.deepamehta.plugins.topicmaps.model.AssociationViewmodel;

import de.deepamehta.plugins.files.service.FilesService;
import de.deepamehta.plugins.files.UploadedFile;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.bind.DatatypeConverter;

import java.io.Writer;
import java.io.FileWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;

import java.util.logging.Logger;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;

import org.codehaus.jettison.json.JSONObject;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.Consumes;
import javax.ws.rs.CookieParam;
import javax.ws.rs.core.MediaType;

import com.sun.jersey.core.util.Base64;


@Path("/import-export")
@Produces("application/json")
public class ImportExportPlugin extends PluginActivator {
    
    private TopicmapsService topicmapsService;
    private FilesService filesService;

    private Logger log = Logger.getLogger(getClass().getName());

    // Service implementation //

    @POST
    @Path("/export/json")
    public Topic exportTopicmapToJSON(@CookieParam("dm4_topicmap_id") long topicmapId) {

	try {
	    log.info("Exporting topicmap #########" + topicmapId);
	    TopicmapViewmodel topicmap = topicmapsService.getTopicmap(topicmapId, true);
	    String json = topicmap.toJSON().toString();
	    InputStream in = new ByteArrayInputStream(json.getBytes("UTF-8"));
	    Topic createdFile = filesService.createFile(in, "/topicmap-" + topicmapId + ".txt");
	    return createdFile;
	} catch (Exception e) {
	    throw new RuntimeException("Export failed", e );
	} 
    }



    @POST
    @Path("/export/svg")
    public void exportTopicmapToSVG(@CookieParam("dm4_topicmap_id") long topicmapId)  throws XMLStreamException {
	try {
	    final int BOX_HEIGHT = 20;
	    final int MARGIN_LEFT = 5;
	    final int MARGIN_TOP = 14;
	    final int ICON_WIDTH = 16;
	    final int ICON_HEIGHT = 16;

	    log.info("Exporting topicmap #########" + topicmapId);
	    TopicmapViewmodel topicmap = topicmapsService.getTopicmap(topicmapId, true);
	    Iterable<TopicViewmodel> topics =topicmap.getTopics();
            Iterable<AssociationViewmodel> associations = topicmap.getAssociations();

	    String SVGfileName = "ExportedTopicamap-" + topicmapId +".svg";
	    SVGRenderer svg = new SVGRenderer(SVGfileName);

	    for (AssociationViewmodel association : associations) {
		String valueAssoc= association.getSimpleValue().toString();
		long topic1Id = association.getRoleModel1().getPlayerId();
		long topic2Id = association.getRoleModel2().getPlayerId();
		TopicViewmodel topic1 = topicmap.getTopic(topic1Id);
		int x1 = topic1.getX();
		int y1 = topic1.getY();

		TopicViewmodel topic2 = topicmap.getTopic(topic2Id);
		int x2 = topic2.getX();
		int y2 = topic2.getY();

		int dx = x2-x1;
		int dy = y2-y1;
		int label_x = dx/2;
		int label_y = dy/2;
		double assocLine = Math.sqrt(Math.pow(dx,2)+Math.pow(dy,2));
		double alpha = Math.asin(dy/assocLine)*180/Math.PI;
		if (dx < 0) {
                    alpha = -alpha;
		    }
		
		svg.line(x1, x2, y1, y2);
		svg.text(label_x, label_y,x1+10,y1+10, valueAssoc, alpha);

	    }


            for (TopicViewmodel topic : topics) {
		String value= topic.getSimpleValue().toString();
		int x = topic.getX();
		int y = topic.getY();
		boolean visibility = topic.getVisibility();
		int boxWidth = value.length() * 9;

		if (!visibility) { continue ;}
		svg.rectangle(x - boxWidth / 2, y - BOX_HEIGHT / 2, boxWidth, BOX_HEIGHT, color(topic.getTypeUri()));
		svg.text(x - boxWidth / 2 + MARGIN_LEFT, y - BOX_HEIGHT / 2 + MARGIN_TOP, value);
		svg.image(x + boxWidth / 2, y, ICON_WIDTH, ICON_HEIGHT, typeIconDataUri(topic.getTypeUri()));
	    }
	    

	    svg.closeDocument();

	} catch (Exception e) {
	    throw new RuntimeException("Export failed", e );
	} 
    }



    @POST
    @Path("/import")
    @Consumes("multipart/form-data")
    public Topic importTopicmap(UploadedFile file) {
	try {
	    String json = file.getString();

	    JSONObject topicmap = new JSONObject(json);
	    JSONObject info = topicmap.getJSONObject("info");

	    JSONArray assocsArray = topicmap.getJSONArray("assocs");
	    JSONArray topicsArray = topicmap.getJSONArray("topics");
	  
	    String origTopicmapName = info.getString("value");
	    Topic importedTopicmap = topicmapsService.createTopicmap("Imported Topicmap: "+ origTopicmapName,"dm4.webclient.default_topicmap_renderer", null);

	    long topicmapId = importedTopicmap.getId();
	    log.info("###### importedTopicapId " + topicmapId);

	    Map<Long, Long> mapTopicIds = new HashMap();
	    importTopics(topicsArray, mapTopicIds, topicmapId);
	    importAssociations(assocsArray,mapTopicIds, topicmapId);
	    return importedTopicmap;	    
	} catch (Exception e) {
	    throw new RuntimeException("Importing failed", e);
	}
    }

	    
	    // Import topics
	    
    private void importTopics(JSONArray topicsArray, Map<Long, Long> mapTopicIds, long topicmapId) {
	for (int i = 0, size = topicsArray.length(); i < size; i++)	{
	    try {
		JSONObject topic =  topicsArray.getJSONObject(i);
		createTopic(topic, mapTopicIds, topicmapId);
	    } catch (Exception e){
		log.warning("Topic not imported!!" + e);
	    }
	}
    }
	    // Import associations

    private void importAssociations(JSONArray assocsArray, Map<Long, Long> mapTopicIds, long topicmapId) {
	for (int i=0, size = assocsArray.length(); i< size; i++) {		    
	    try {
		JSONObject association = assocsArray.getJSONObject(i);
		createAssociation(association, mapTopicIds, topicmapId);
	    } catch (Exception e) {
		log.warning("Association not imported");
	    }
	}
    }
    



    // Hook implementation //
    
    @Override
    @ConsumesService({TopicmapsService.class, FilesService.class })
    public void serviceArrived(PluginService service) {
	if (service instanceof TopicmapsService) {
            topicmapsService = (TopicmapsService) service;
        } else if (service instanceof FilesService) {
            filesService = (FilesService) service;
        }
    }
   
    @Override
    public void serviceGone(PluginService service) {
	if (service == topicmapsService) {
	    topicmapsService = null;
        } else if (service == filesService) {
	    filesService = null;
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


    private String typeIconDataUri(String typeUri) throws IOException {
	TopicType topicType = dms.getTopicType(typeUri);
	String iconPath = (String) topicType.getViewConfig("dm4.webclient.view_config","dm4.webclient.icon");
	int sep = iconPath.indexOf("/", 2);
	String pluginPath = iconPath.substring(1, sep);
	Plugin plugin = dms.getPlugin(pluginPath);
	String imagePath = "web"+iconPath.substring(sep);
	
	InputStream iconIS = plugin.getResourceAsStream(imagePath);
	ByteArrayOutputStream baos = new ByteArrayOutputStream();
	byte [] buffer = new byte[1024];
	int count = 0;
	while ( (count = iconIS.read(buffer)) != -1 ) {
	    baos.write(buffer, 0, count);
	}

	byte [] fileContent = baos.toByteArray();
	//all chars in encoded are guaranteed to be 7-bit ASCII
	byte[] encoded = Base64.encode(fileContent);
	String imgBase64Str = new String(encoded);
	log.info("##### IMG BASE64 " + imgBase64Str);
	
	if (iconPath == null) {
	    iconPath = "/de.deepamehta.webclient/images/ball-gray.png";
	}

	return "data:image/png;base64," + imgBase64Str;
		 
   }

  
    private void createTopic(JSONObject topic, Map<Long, Long> mapTopicIds, long topicmapId) throws JSONException {
	TopicModel model = new TopicModel(topic);
	CompositeValueModel viewProps =new CompositeValueModel(topic.getJSONObject("view_props")); 
	long origTopicId = model.getId();
	
	Topic newTopic = dms.createTopic(model, null);
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
	Association newAssociation = dms.createAssociation(assocModel, null);
	long assocId = newAssociation.getId();
	topicmapsService.addAssociationToTopicmap(topicmapId, assocId);		 
	
    }

}

