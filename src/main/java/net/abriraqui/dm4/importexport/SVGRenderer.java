package net.abriraqui.dm4.importexport;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.stream.XMLOutputFactory;

import java.io.FileWriter;

class SVGRenderer {

    private XMLStreamWriter svgWriter;

    public SVGRenderer(String filename) {
	try {
	    XMLOutputFactory xof = XMLOutputFactory.newInstance();
	    svgWriter = xof.createXMLStreamWriter(new FileWriter(filename));
	    svgWriter.writeStartDocument();
	    svgWriter.writeDTD("<!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 20000802//EN\" " 
			       + "\"http://www.w3.org/TR/2000/CR-SVG-20000802/DTD/svg-20000802.dtd\">");
	    svgWriter.writeStartElement("svg");
	    svgWriter.writeAttribute("width", "1200");
	    svgWriter.writeAttribute("height", "1200");
	    svgWriter.writeAttribute("xmlns","http://www.w3.org/2000/svg");
	    svgWriter.writeAttribute("xmlns:xlink","http://www.w3.org/1999/xlink");
	} catch (Exception e) {
	    throw new RuntimeException("Construction SVGRenderer failed", e);
	}
    }

    public void closeDocument() throws XMLStreamException {

	svgWriter.writeEndDocument(); // closes svg element
	svgWriter.flush();
	svgWriter.close();

    }

    public void line(int x1, int x2, int y1, int y2) throws XMLStreamException {

	svgWriter.writeEmptyElement("line");
	svgWriter.writeAttribute("x1", Integer.toString(x1));
	svgWriter.writeAttribute("x2", Integer.toString(x2));
	svgWriter.writeAttribute("y1",  Integer.toString(y1));
	svgWriter.writeAttribute("y2",  Integer.toString(y2));
	svgWriter.writeAttribute("stroke", "lightgray");
	svgWriter.writeAttribute("stroke-width", "3");

    }

    public void rectangle(int x, int y, int width, int height, String color) throws XMLStreamException {

        svgWriter.writeEmptyElement("rect");
        svgWriter.writeAttribute("x", Integer.toString(x));
        svgWriter.writeAttribute("y", Integer.toString(y));
        svgWriter.writeAttribute("width", Integer.toString(width));
        svgWriter.writeAttribute("height", Integer.toString(height));
        svgWriter.writeAttribute("fill", color);

    }

    public void text(int x, int y, String value, String color) throws XMLStreamException {
	text(x, y, 0, 0, value, color, 0);
    }

    public void text(int x, int y, int x1, int y1, String value, String color,  double alpha) throws XMLStreamException {

        svgWriter.writeStartElement("text");
        svgWriter.writeAttribute("x", Integer.toString(x));
        svgWriter.writeAttribute("y", Integer.toString(y));
        svgWriter.writeAttribute("font-size", "0.8em");
        svgWriter.writeAttribute("fill", color);
        svgWriter.writeAttribute("transform","translate(" + x1 + "," + y1 +")" + " " + "rotate(" + Double.toString(alpha) + "," + x + "," + y  + ")");
        svgWriter.writeCharacters(value);
        svgWriter.writeEndElement();

    }

    public void image(int x, int y, int imgWidth, int imgHeight, String imgUri) throws XMLStreamException {

        svgWriter.writeEmptyElement("image");
        svgWriter.writeAttribute("x", Integer.toString(x));
        svgWriter.writeAttribute("y", Integer.toString(y));
        svgWriter.writeAttribute("width", Integer.toString(imgWidth));
        svgWriter.writeAttribute("height", Integer.toString(imgHeight));
        svgWriter.writeAttribute("xlink:href", imgUri);

    }
    
    public void startGroupElement (long elementId) throws XMLStreamException {
        
        svgWriter.writeStartElement("g");
        svgWriter.writeAttribute("id", "" + elementId + "");

    }
    
    public void endElement () throws XMLStreamException {
        
        svgWriter.writeEndElement();

    }

}