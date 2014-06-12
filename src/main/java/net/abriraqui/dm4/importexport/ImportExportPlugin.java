package net.abriraqui.dm4.importexport;

import de.deepamehta.core.osgi.PluginActivator;
import de.deepamehta.core.util.JavaUtils;
import de.deepamehta.core.util.DeepaMehtaUtils;
import de.deepamehta.core.service.PluginService;
import de.deepamehta.core.service.annotation.ConsumesService;
import de.deepamehta.core.Topic;
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


import java.io.Writer;
import java.io.FileWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.ByteArrayInputStream;

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
	    log.info("Exporting topicmap #########" + topicmapId);
	    TopicmapViewmodel topicmap = topicmapsService.getTopicmap(topicmapId, true);
	    Iterable<TopicViewmodel> topics =topicmap.getTopics();
            Iterable<AssociationViewmodel> associations = topicmap.getAssociations();

	    String SVGfileName = "ExportedTopicamap-" + topicmapId +".svg";
	    XMLOutputFactory xof = XMLOutputFactory.newInstance();
	    XMLStreamWriter svgWriter = null;
	    svgWriter = xof.createXMLStreamWriter(new FileWriter(SVGfileName));

	    svgWriter.writeStartDocument();
	    svgWriter.writeDTD("<!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 20000802//EN\" " 
			    + "\"http://www.w3.org/TR/2000/CR-SVG-20000802/DTD/svg-20000802.dtd\">");
	    svgWriter.writeStartElement("svg");
	    svgWriter.writeAttribute("width", "1200");
	    svgWriter.writeAttribute("height", "1200");
	    svgWriter.writeAttribute("xmlns","http://www.w3.org/2000/svg");

	    for (AssociationViewmodel association : associations) {
		long topic1Id = association.getRoleModel1().getPlayerId();
		long topic2Id = association.getRoleModel2().getPlayerId();
		TopicViewmodel topic1 = topicmap.getTopic(topic1Id);
		int x1 = topic1.getX();
		int y1 = topic1.getY();

		TopicViewmodel topic2 = topicmap.getTopic(topic2Id);
		int x2 = topic2.getX();
		int y2 = topic2.getY();
	
		svgWriter.writeEmptyElement("line");
		svgWriter.writeAttribute("x1", Integer.toString(x1));
		svgWriter.writeAttribute("x2", Integer.toString(x2));
		svgWriter.writeAttribute("y1",  Integer.toString(y1));
		svgWriter.writeAttribute("y2",  Integer.toString(y2));
		svgWriter.writeAttribute("stroke", "lightgray");
		svgWriter.writeAttribute("stroke-width", "2");
		
	    }

            for (TopicViewmodel topic : topics) {
		String value= topic.getSimpleValue().toString();
		int x = topic.getX();
		int y = topic.getY();
		boolean visibility = topic.getVisibility();
		int valueWidth = value.length()*9;

		if (!visibility) { continue ;}
		svgWriter.writeEmptyElement("rect");
		svgWriter.writeAttribute("x", Integer.toString(x));
		svgWriter.writeAttribute("y", Integer.toString(y));
		svgWriter.writeAttribute("width", Integer.toString(valueWidth));
		svgWriter.writeAttribute("height", "20");

		svgWriter.writeAttribute("fill", color(topic.getTypeUri()));   

		svgWriter.writeStartElement("text");
		svgWriter.writeAttribute("x", Integer.toString(x+5));
		svgWriter.writeAttribute("y", Integer.toString(y+14));
		svgWriter.writeCharacters(value);
		svgWriter.writeEndElement();
		
	    }
	    
	

	    svgWriter.writeEndDocument(); // closes svg element
	    svgWriter.flush();
	    svgWriter.close();

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
	    return "red";
	} else if (typeUri.equals("dm4.contacts.person")) {
	    return "blue";
	} else if (typeUri.equals("dm4.notes.note")) {
	    return "yellow";
	} else {
	    return "lightblue";
	}
    }

    private String icon(String typeUri) {
	TopicType topicType = dms.getTypeUri(typeUri);
	ViewConfiguration viewConfig = topicType.getViewConfig();
	for (Topic topicConf: viewConfig.getConfigTopics()){
	    if (topicConf.getTypeUri().equals("dm4.webclient.icon")){
		String path = topicConf.getSimpleValue().toString();
	    }
	}
	return path;
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

