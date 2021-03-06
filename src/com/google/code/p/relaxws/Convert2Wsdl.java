/*
 * Copyright 2008 Jason Sando
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.google.code.p.relaxws;

import com.thaiopensource.relaxng.edit.SchemaCollection;
import com.thaiopensource.relaxng.input.InputFailedException;
import com.thaiopensource.relaxng.input.InputFormat;
import com.thaiopensource.relaxng.input.parse.compact.CompactParseInputFormat;
import com.thaiopensource.relaxng.output.LocalOutputDirectory;
import com.thaiopensource.relaxng.output.OutputDirectory;
import com.thaiopensource.relaxng.output.OutputFormat;
import com.thaiopensource.relaxng.output.xsd.XsdOutputFormat;
import com.thaiopensource.xml.sax.ErrorHandlerImpl;
import com.google.code.p.relaxws.parser.*;
import com.thaiopensource.resolver.BasicResolver;

import java.io.*;
import java.net.URL;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Convert from relaxws-wiz to wsdl.
 *
 * 1. parse the input file
 * 2. append all rnc blocks into a single buffer, then convert to XSD, to be
 *    embedded in the wsdl.
 * 3. Output wsdl.
 */
public class Convert2Wsdl {

	private PrintWriter out;
	private ASTservice tree;
	private final int MAX_COUNT = 3;
	private String encoding;

	private static void usage (String reason) {
		if (reason != null)
			System.err.println ("Command failed: " + reason);
		System.err.println ("\nUSAGE:");
		System.err.println("Convert2Wsdl [-d output-folder] [-encoding source-encoding] <input.rws>");
		System.exit (1);
	}

	private static void fail (String reason) {
		if (reason != null)
			System.err.println ("Command failed: " + reason);
		System.exit (1);
	}

	private static void fail (String reason,int ret) {
		if (reason != null)
			System.err.println ("Command failed: " + reason);
		System.exit (ret);
	}

	private static String fileBase (String name) {
		int dot = name.lastIndexOf('.');
		if (dot != -1) {
			name = name.substring (0, dot);
		}
		return name;
	}

	public static void main(String[] args) throws Exception {

		String outputPath = ".";
		String inputFilePath = null;
		String encoding = System.getProperty("file.encoding");
		int lastArg = args.length - 1;
		for (int i = 0; i < args.length; i++) {
			if ("-d".equals (args[i]) && (i < lastArg)) {
				outputPath = args[i + 1];
				i++;
			} else if ("-encoding".equals (args[i]) && (i < lastArg)) {
				encoding = args[i + 1];
				i++;
			} else if (args[i].startsWith("-")) {
				usage("unrecognized option " + args[i]);
			} else {
				inputFilePath = args[i];
				String flist[]=new File(inputFilePath).list();
				if(flist!=null && flist.length>0) {
					for(String s:flist){
						convert(outputPath,s,encoding);
					}
				}else{
					convert(outputPath,inputFilePath,encoding);
				}
			}
		}

	}
	
	private static void convert(String outputPath, String inputFilePath,String encoding)throws Exception{
		if (inputFilePath == null) {
			usage(null);
		}

		File inputFile = new File (inputFilePath);
		if (!inputFile.exists()) {
			fail ("'" + inputFilePath + "' not found.",0);
		}
		if (outputPath == null) {
			outputPath = inputFile.getParent();
		}

		File outputFileDir = new File (outputPath);
		if (!outputFileDir.exists()) {
			if (!outputFileDir.mkdirs()) {
				fail ("failed to create output folder '" + outputPath + "'");
			}
		}
		System.err.println("Convert2Wsdl: process '" + inputFile.getName() );

		BufferedReader rdr = new BufferedReader (new InputStreamReader( new FileInputStream(inputFile), encoding ));
		RelaxWizParser p = new RelaxWizParser (rdr);
		ASTservice tree = p.service ();

		File outputFile = new File(outputFileDir, fileBase(inputFile.getName()) + ".wsdl");

		PrintWriter out = new PrintWriter(outputFile,"UTF-8");

		Convert2Wsdl converter = new Convert2Wsdl();
		converter.out = out;
		converter.tree = tree;
		converter.encoding=encoding;
		converter.convert();
		out.flush();
		out.close();
		
		System.err.println("    wsdl created: '" + outputFile.getPath());
		converter=null;
		out=null;
	}

	private void convert() throws Exception {

		String ns = tree.getNamespace();

		out.print ("<?xml version=\"1.0\"?>\n");
		out.print ("<definitions name=\"" + tree.getName() + "\"\n" +
				"             targetNamespace=\"" + ns + "\"\n" +
				"             xmlns:tns=\"" + ns + "\"\n" +
				"             xmlns:soap=\"http://schemas.xmlsoap.org/wsdl/soap/\"\n" +
				"             xmlns=\"http://schemas.xmlsoap.org/wsdl/\">\n" +
				"\n" +
				"  <documentation>"+tree.getDocumentation()+"</documentation>\n"+
				"  <types>\n");

		// Make a pass through and assign all message names, and build proper rnc block
		Set<String> messageNames = new HashSet<String>();
		StringBuffer rncBuff = new StringBuffer();
		if( tree.getOption("fully-qualified").equals("true") ){
			rncBuff.append ("default namespace = \"" + ns + "\"\n");
		}
		
		//append all defined namespaces on the level of service
		for(Map.Entry<String,String> nse : tree.getNamespaces().entrySet()){
			rncBuff.append ("namespace "+nse.getKey()+"= \"" + nse.getValue() + "\"\n");
		}
		//rncBuff.append ("(\n");
		for (Node portNode: tree.getChildren()) {
			if (portNode instanceof ASTtypesDecl) {
				rncBuff.append(((ASTtypesDecl) portNode).getRnc());
				continue;
			}
			if ( !(portNode instanceof ASTportDecl) ) {
				continue;
			}

			ASTportDecl port = (ASTportDecl) portNode;

			// enumerate operations in this port
			for (Node opNode: port.getChildren()) {
				ASToperationDecl op = (ASToperationDecl) opNode;

				// children of op node
				for (Node msgNode: op.getChildren()) {
					ASTMessageDef message = (ASTMessageDef) msgNode;
					message.setDefaultMessageName(op.getName());

					if (messageNames.contains(message.getMessageName())) {
						// todo: loop searching for unique name
						message.setMessageName(message.getMessageName() + "1");
					} else {
						messageNames.add(message.getMessageName());
					}

					if (message.getName() == null) {
						message.setDefaultName (op.getName());
					}

					rncBuff.append (message.getName() + " = ");
					rncBuff.append ("element tns:" + message.getName() + " {\n");
					String s = message.getRnc();
					if (s.trim().length() == 0) {
						s = "text";
					}
					rncBuff.append (s);
					rncBuff.append ("}\n");
				}
			}
		}
		// rncBuff.append (")");
		// convert rnc to xsd
		rncBuff = replaceExternalRefWithContent(rncBuff);
		
		String xsdText = toXsd(rncBuff.toString());
		out.print (xsdText);

		out.print ("  </types>\n");

		// declare messages for each in and out of each operation for each port (must be unique)
		out.println ();
		for(ASTportDecl port: tree.getPorts()) {
			// enumerate operations in this port
			for (Node opNode: port.getChildren()) {
				ASToperationDecl op = (ASToperationDecl) opNode;

				// children of op node
				for (Node msgNode: op.getChildren()) {
					ASTMessageDef message = (ASTMessageDef) msgNode;
					// declare message type
					out.println ();
					out.println ("  <message name=\"" + message.getMessageName() + "\">");
					out.println ("    <part name=\"body\" element=\"tns:" + message.getName() + "\"/>");
					out.println ("  </message>");
				}

			}

			out.println ();
			out.println ("  <portType name=\"" + port.getName() + "\">");
			for (Node opNode: port.getChildren()) {
				ASToperationDecl op = (ASToperationDecl) opNode;

				out.println ("    <operation name=\"" + op.getName() + "\">");
				if(op.getDocumentation()!=null && op.getDocumentation().length()!=0)
					out.println ("      <documentation>" + op.getDocumentation() + "</documentation>");

				// children of op node
				for (Node msgNode: op.getChildren()) {
					ASTMessageDef message = (ASTMessageDef) msgNode;

					switch (message.getType()) {
						case In:
							out.println ("      <input message=\"tns:" + message.getMessageName() + "\"/>");
							break;

						case Out:
							out.println ("      <output message=\"tns:" + message.getMessageName() + "\"/>");
							break;

						case Fault:
							out.println ("      <fault message=\"tns:" + message.getMessageName() + "\"/>");
							break;
					}
				}

				out.println ("    </operation>");
			}
			out.println ("  </portType>");
			
			if(tree.getEndpoints().size()==0) {
				//render old style binding and fake endpoint for each port definition
				// binding to soap
				out.println ();
				out.println ("  <binding name=\"" + port.getName() + "SoapBinding\" type=\"tns:" + port.getName() + "\">");
				out.println ("    <soap:binding style=\"document\" transport=\"http://schemas.xmlsoap.org/soap/http\"/>");
				for (Node opNode: port.getChildren()) {
					ASToperationDecl op = (ASToperationDecl) opNode;

					out.println ("    <operation name=\"" + op.getName() + "\">");
					out.println ("      <soap:operation soapAction=\"urn:" + op.getName() + "\"/>");
					out.println ("      <input>\n" +
							"        <soap:body use=\"literal\"/>\n" +
							"      </input>\n" +
							"      <output>\n" +
							"        <soap:body use=\"literal\"/>\n" +
							"      </output>\n" +
							"      <!--<fault>\n" +
							"        <soap:fault use=\"literal\"/>\n" +
							"      </fault>-->");
					out.println ("    </operation>");
					// TODO: uncomment soap:fault above once faults are truly implemented
				}
				out.println ("  </binding>");

				out.println();
				out.println ("  <service name=\"" + tree.getName() + ( tree.getPortCount()==1?"":port.getName() ) + "\">\n" +
						"    <port name=\"" + port.getName() + "\" binding=\"tns:" + port.getName() + "SoapBinding\">\n" +
						"      <soap:address location=\"http://example.com/" + tree.getName() + "\"/>\n" +
						"    </port>\n" +
						"  </service>");
			}
		}
		if(tree.getEndpoints().size()>0){
			int counter = 0;
			LinkedHashSet<String> bindingSet = new LinkedHashSet();
			StringBuilder serviceOut = new StringBuilder(1000);
			for(ASTepDecl ep: tree.getEndpoints()) {
				counter++;
				String epType = ep.opt().getValue("type");
				String portRefName = ep.opt().getValue("interface",null);
				
				for(ASTportDecl port : tree.getPorts()) {
					String bindName = port.getName()+epType.substring(0,1).toUpperCase()+epType.substring(1);
					if(portRefName!=null && !portRefName.equals(port.getName()))continue; //reference to the interface defined but not matches to current one
					if(bindingSet.contains(bindName))continue; //current interface already binded with specific binding type (soap,...)
					bindingSet.add(bindName);
					out.println ();
					out.println ("  <binding name=\"" + bindName + "Binding\" type=\"tns:" + port.getName() + "\">");
					if( "soap".equals( epType ) ){
						out.println ("    <soap:binding style=\"document\" transport=\"http://schemas.xmlsoap.org/soap/http\"/>");
						for (Node opNode: port.getChildren()) {
							ASToperationDecl op = (ASToperationDecl) opNode;

							out.println ("    <operation name=\"" + op.getName() + "\">");
							out.println ("      <soap:operation soapAction=\"urn:" + op.getName() + "\"/>");
							out.println ("      <input>\n" +
									"        <soap:body use=\"literal\"/>\n" +
									"      </input>\n" +
									"      <output>\n" +
									"        <soap:body use=\"literal\"/>\n" +
									"      </output>\n" +
									"      <!--<fault>\n" +
									"        <soap:fault use=\"literal\"/>\n" +
									"      </fault>-->");
							out.println ("    </operation>");
							// TODO: uncomment soap:fault above once faults are truly implemented
							
							serviceOut.append(
								"    <port name=\"" + (ep.getName().length()==0?bindName:ep.getName()) + "\" binding=\"tns:" + bindName + "Binding\">\n" +
								"      <soap:address location=\""+ ep.opt().getValue("address") +"\"/>\n" +
								"    </port>\n" 
							);
						}
						out.println ("  </binding>");
					} //else is an http or ....
					
				}
				
			}
			out.println();
			out.println ("  <service name=\"" + tree.getName() + "\">\n");
			out.println (serviceOut);
			out.println ("  </service>");
		}	
		out.print ("</definitions>\n");
	}

	private static String toXsd (String rnc) throws Exception {

		// write the rnc to a temp file
		File rncInput = File.createTempFile("relaxwiz", ".rnc");
		PrintWriter fw = new PrintWriter (rncInput,"UTF-8");
		fw.write(rnc);
		fw.close();

		// Use Trang to convert to an XSD file
		InputFormat inFormat = new CompactParseInputFormat();
		ErrorHandlerImpl handler = new ErrorHandlerImpl();
		SchemaCollection sc = null;
		try {
			String uri=rncInput.toURI().toURL().toString();
			sc = inFormat.load(uri, new String[0], "xsd", handler,BasicResolver.getInstance());
		} catch (InputFailedException e) {
			System.err.println("Error in RNC preprocessor, source follows:");
			int line = 0;
			for (String s: rnc.split("\n")) {
				line++;
				System.err.printf("%3d: %s\n", line, s);
			}
			System.exit (1);
		}
		OutputFormat of = new XsdOutputFormat();
		File xsdOutput = File.createTempFile("relaxwiz", ".xsd");
		OutputDirectory od = 
				new com.google.code.p.relaxws.trang.SingleFileOutputDirectory(
				//new LocalOutputDirectory(
					sc.getMainUri(),
					xsdOutput,
					"xsd",
					"UTF-8",
					80, //line
					2   //indent
				);
		String[] outParams = new String[]{new URL("file", "", xsdOutput.getAbsolutePath()).toString()};
		of.output(sc, od, new String[]{}, "rnc", handler);

		// read in file and return as string.
		BufferedReader reader = new BufferedReader(new InputStreamReader( new FileInputStream(xsdOutput), "UTF-8" ));
		String line;
		StringBuffer buf = new StringBuffer();
		while ((line = reader.readLine()) != null) {
			if (line.startsWith("<?xml") || line.matches("\\s*<xs:import\\s+[^>]+>")) {
				continue;
			}
			buf.append (line).append ('\n');
		}
		reader.close();
		
		return buf.toString();
	}
	
	private StringBuffer replaceExternalRefWithContent(StringBuffer rncBuff) {
		//i hate this part of code
		//trang does not support `external` keyword
		//this is a hot fix
		
		StringBuffer nsBuff = new StringBuffer(); //all namespace declarations from externals must go first.
		StringBuffer tempBuff = new StringBuffer(rncBuff);
		int step = 0;
		Pattern pattern = Pattern.compile("([# \t\f]*external\\s+\")(.*?)(\")");
		Pattern nsPattern = Pattern.compile("(?sm)(^\\s*((#[^\r\n]*|namespace\\s+\\w+\\s*=\\s*\"[^\"]*\"[ \t\f]*)[\r\n]*)+)(.*)");
		if (tempBuff != null) {
				while (pattern.matcher(tempBuff.toString()).find() && step++ < MAX_COUNT) {
				Matcher matcher = pattern.matcher(tempBuff.toString());
				StringBuffer sb = new StringBuffer();
				while (matcher.find()) {
					if( !matcher.group(1).trim().startsWith("#") ) { 
						//not commented
						String extFileContent = getFileContent(matcher.group(2));
						Matcher nsMatcher = nsPattern.matcher(extFileContent);
						if(nsMatcher.matches()){
							nsBuff.append(nsMatcher.replaceFirst("$1"));
							extFileContent = nsMatcher.replaceFirst("$4"); //keep the rest
						}
						matcher.appendReplacement(sb, extFileContent );
					}else matcher.appendReplacement(sb, "\n" );
					
				}
				matcher.appendTail(sb);
				tempBuff = sb;
			}
		}
		
		//String s=new File("D:/11/svn/EXT/relax-ws/trunk/lib/world2.rnc").getText()
		//s.replaceFirst('(?sm)(^\\s*((#[^\r\n]*|namespace\\s+\\w+\\s*=\\s*\"[^\"]*\"[ \t\f]*)[\r\n]*)+)(.*)','$1')
		tempBuff.insert(0,nsBuff);
		return tempBuff;
	}
	
	private String getFileContent(String filePath) {
		try {
			if(filePath.startsWith("file:///"))filePath=filePath.substring(8);
			BufferedReader r=new BufferedReader( new InputStreamReader( new FileInputStream(filePath) , encoding ) );
			StringBuffer out=new StringBuffer();
			int ch;
			while( ( ch = r.read() )!=-1 ) {
				out.append( (char)ch );
			}
			r.close();
			return out.toString();
		}catch(Exception e){
			fail("Can't read file: "+new File(filePath).getAbsolutePath()+"\n\t"+e);
			return ""; // non accessible code
		}
	}

}

