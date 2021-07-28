/*******************************************************************************
 * CustomerView Function version 1.0.
 *
 * Copyright (c)  2021,  Oracle and/or its affiliates.
 * Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.
 ******************************************************************************/
package com.oracle.ateam.customerview.fn;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.UUID;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.util.Properties;
import java.util.Date;
import com.sun.mail.smtp.*;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;

import com.fnproject.fn.api.InputEvent;
import com.fnproject.fn.api.QueryParameters;
import com.fnproject.fn.api.httpgateway.HTTPGatewayContext;
import com.oracle.bmc.Region;
import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.auth.ResourcePrincipalAuthenticationDetailsProvider;
import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.objectstorage.requests.GetObjectRequest;
import com.oracle.bmc.objectstorage.requests.ListObjectsRequest;
import com.oracle.bmc.objectstorage.responses.GetObjectResponse;
import com.oracle.bmc.objectstorage.responses.ListObjectsResponse;
import com.oracle.bmc.objectstorage.*;
import com.oracle.bmc.secrets.SecretsClient;
import com.oracle.bmc.secrets.model.Base64SecretBundleContentDetails;
import com.oracle.bmc.secrets.requests.GetSecretBundleRequest;
import com.oracle.bmc.secrets.responses.GetSecretBundleResponse;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;

import org.json.JSONObject;
import org.json.XML;
import org.json.XMLParserConfiguration;
import org.json.JSONException;
import org.w3c.dom.Document;

import oracle.soda.OracleCollection;
import oracle.soda.OracleCursor;
import oracle.soda.OracleDatabase;
import oracle.soda.OracleDocument;
import oracle.soda.OracleException;
import oracle.soda.rdbms.OracleRDBMSClient;

import org.apache.commons.io.IOUtils;

import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.*;
import javax.xml.parsers.ParserConfigurationException;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import java.util.regex.Pattern;

public class CustomerViewFn {

    private OracleCollection collection;
    private static final Logger LOGGER = Logger.getLogger("CustomerViewFn");
    private OracleDocument metaDoc;
    OracleDatabase db = null;
    Connection con = null;
    private Pattern pattern = Pattern.compile("-?\\d+(\\.\\d+)?");

    // OCI
    private static final String OCI_REGION = System.getenv().get("OCI_REGION");
    
    // For Email Notification
    private static final String SMTP_SERVER = System.getenv().get("SMTP_SERVER");
    private static final String SMTP_SERVER_PORT = System.getenv().get("SMTP_SERVER_PORT");
    private static final String SMTP_USER = System.getenv().get("SMTP_USER");
    private String emailPwd = null;

    // DB Connection
    private final File walletDir = new File(System.getenv().get("WALLET_DIR"), "wallet");
    private final String namespace = System.getenv().get("NAMESPACE");
    private final String bucketName = System.getenv().get("BUCKET_NAME");
    private final String dbUrl = System.getenv().get("DB_URL");
    private final String dbUser = System.getenv().get("DB_USER");
    private String dbPwd = null;

    // For Vault
    private final String emailSecretId = System.getenv("EMAIL_SECRET_OCID");
    private final String dbSecretId = System.getenv("DB_SECRET_OCID");
    private SecretsClient secretsClient;

    /**
     * Constructor
     * 
     * - Initailize Secret client - Get the secret for email server and json db. -
     * Create the database connection
     */
    public CustomerViewFn() {
        initSecretClient();
        try {
            emailPwd = getSecretValue(emailSecretId);
            dbPwd = getSecretValue(dbSecretId);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Unable get Secret");
        }
        Boolean downloadWallet = needWalletDownload();
        if (Boolean.TRUE.equals(downloadWallet)) {
            LOGGER.log(Level.INFO, "Start wallet download...");
            getDBWallet();
            LOGGER.log(Level.INFO, "End wallet download!");
        }

        try {
            db = connectDB();
            collection = getExistingCollection(db, "CustomerCollection");
            LOGGER.log(Level.INFO, "Database connected");
        } catch (OracleException e) {
            LOGGER.log(Level.SEVERE, "Unable to connect to the JSON database");
        }
    }

    /**
     * Handle all incoming http request. Currently it will only handle HTTP GET and
     * POST.
     * 
     * @param ctx   http context
     * @param input input data payload
     * @return result as string.
     */
    public String handleRequest(HTTPGatewayContext ctx, InputEvent input) {
        String result = "";
        String httpMethod = ctx.getMethod();
        if (httpMethod.equalsIgnoreCase("Get")) {
            String reqURL = ctx.getRequestURL();

            if (reqURL.matches("/customer360/loan(.*)")) {
                LOGGER.log(Level.INFO, "Retriving customer data");
                result = processGetDataRequest(ctx);
            } else if (reqURL.matches("/customer360/verify(.*)")) {
                String uuid = generateUUI();
                LOGGER.log(Level.INFO, "Generate UUID and add it to the json payload");
                QueryParameters qp = ctx.getQueryParameters();
                String optyId = qp.getValues("loanid").get(0);
                if (isNumeric(optyId)) {
                    List<String> docKeyList = getDocQueryByExample("{ \"OptyId\" : " + optyId + "}", db);
                    if (!docKeyList.isEmpty()) {
                        LOGGER.log(Level.INFO, "Found exisiting Opty data, update or add new data");
                        for (String docKey : docKeyList) {
                            OracleDocument optyDoc = getDocById(docKey);
                            try {
                                if (optyDoc != null) {
                                    JSONObject currentJsonObj = new JSONObject(optyDoc.getContentAsString());
                                    currentJsonObj.put("uuid", uuid);
                                    String email = currentJsonObj.get("EmailAddress").toString();

                                    result = currentJsonObj.toString();
                                    updateDocument(db, docKey, result, "");
                                    sendMail(email, "Your Unique Code",
                                            "Your unique code to access your application: " + uuid);
                                    result = StringToJSON(
                                            "Please check your email for your unique code to access your application");
                                    return result;
                                }
                            } catch (OracleException e) {
                                LOGGER.log(Level.SEVERE, "Unable handle the request");
                            }
                        }
                    }
                }else{
                    LOGGER.log(Level.WARNING, "Invalid loan number");
                    result = StringToJSON("Invalid loan number");
                    return result;
                }
                LOGGER.log(Level.WARNING, "Unable to generate Unique Code");
                result = StringToJSON("Unable to generate Unique Code");
            } else {
                LOGGER.log(Level.WARNING, "Invalid URL");
                result = "{\"Error\": \"Invalid URL\"}";
            }
        } else if (httpMethod.isEmpty() || httpMethod.equalsIgnoreCase("Post")) {
            result = processPostRequest(ctx, input);
        } else { // All other HTTP methods
            LOGGER.log(Level.WARNING, "HTTP Method not supported");
            result = "{\"Error\" : \"HTTP Method not supported\"}";
        }
        return result;
    }

    /**
     * Get the Database Wwallet from the object storage
     */
    private void getDBWallet() {
        final ResourcePrincipalAuthenticationDetailsProvider provider = ResourcePrincipalAuthenticationDetailsProvider
                .builder().build();

        ObjectStorage osClient = new ObjectStorageClient(provider);
        osClient.setRegion(Region.valueOf(OCI_REGION));

        LOGGER.log(Level.INFO, "Retrieving a list of all objects in object storage bucket");
        // List all objects in wallet bucket
        ListObjectsRequest listObjectsRequest = ListObjectsRequest.builder().namespaceName(namespace)
                .bucketName(bucketName).build();
        ListObjectsResponse listObjectsResponse = osClient.listObjects(listObjectsRequest);
        LOGGER.log(Level.INFO, "List retrieved. Starting download of each object");

        // Iterate over each wallet file, downloading it to the Function's Docker
        // container
        listObjectsResponse.getListObjects().getObjects().stream().forEach(objectSummary -> {
            LOGGER.log(Level.INFO, "Downloading wallet file: {0}", objectSummary.getName());

            GetObjectRequest objectRequest = GetObjectRequest.builder().namespaceName(namespace).bucketName(bucketName)
                    .objectName(objectSummary.getName()).build();
            GetObjectResponse objectResponse = osClient.getObject(objectRequest);

            try {
                File f = new File(walletDir + "/" + objectSummary.getName());
                FileUtils.copyToFile(objectResponse.getInputStream(), f);
                LOGGER.log(Level.INFO, "Stored wallet file");
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Error getting wallet file");
            }
        });
        try {
            osClient.close();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Closing object storage client error");
        }
    }

    /**
     * Check if the wallet folder exist.
     * 
     * @return true/false
     */
    private Boolean needWalletDownload() {
        if (walletDir.exists()) {
            LOGGER.log(Level.INFO, "Wallet exists, do not download it again");
            return false;
        } else {
            LOGGER.log(Level.INFO, "Did not find a wallet, download one");
            return walletDir.mkdirs();
        }
    }

    /**
     * Send email to a SMTP server
     * 
     * @param recipient
     * @param emailSubject
     * @param emailMessage
     */
    private void sendMail(String recipient, String emailSubject, String emailMessage) {
        Properties prop = System.getProperties();
        prop.put("mail.smtp.host", SMTP_SERVER);
        prop.put("mail.smtp.auth", "true");
        prop.put("mail.smtp.port", SMTP_SERVER_PORT);

        Session session = Session.getInstance(prop, null);
        Message msg = new MimeMessage(session);

        try {
            msg.setFrom(new InternetAddress(SMTP_USER));
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient, false));
            msg.setSubject(emailSubject);
            msg.setText(emailMessage);
            msg.setSentDate(new Date());
            SMTPTransport smtp = (SMTPTransport) session.getTransport("smtp");
            smtp.connect(SMTP_SERVER, SMTP_USER, emailPwd);
            smtp.sendMessage(msg, msg.getAllRecipients());
            smtp.close();
        } catch (MessagingException e) {
            LOGGER.log(Level.SEVERE, "Unable to send email: {0}", e.getMessage());
        }

    }

    /**
     * Initialize the OCI secret client.
     */
    private void initSecretClient() {
        String version = System.getenv("OCI_RESOURCE_PRINCIPAL_VERSION");
        BasicAuthenticationDetailsProvider provider = null;
        if (version != null) {
            provider = ResourcePrincipalAuthenticationDetailsProvider.builder().build();
        } else {
            try {
                provider = new ConfigFileAuthenticationDetailsProvider("~/.oci/config", "DEFAULT");
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Unable initialize secret client: {0}", e.getMessage());
            }
        }
        secretsClient = new SecretsClient(provider);
        secretsClient.setRegion(Region.valueOf(OCI_REGION));
    }

    /**
     * Get the secret value from the OCI vault.
     * 
     * @param secretOcid The OCI ID of the secret
     * @return the secret in plain text format.
     * @throws IOException
     */
    private String getSecretValue(String secretOcid) throws IOException {

        // create get secret bundle request
        GetSecretBundleRequest getSecretBundleRequest = GetSecretBundleRequest.builder().secretId(secretOcid)
                .stage(GetSecretBundleRequest.Stage.Current).build();

        // get the secret
        GetSecretBundleResponse getSecretBundleResponse = secretsClient.getSecretBundle(getSecretBundleRequest);

        // get the bundle content details
        Base64SecretBundleContentDetails base64SecretBundleContentDetails = (Base64SecretBundleContentDetails) getSecretBundleResponse
                .getSecretBundle().getSecretBundleContent();

        // decode the encoded secret
        byte[] secretValueDecoded = Base64.decodeBase64(base64SecretBundleContentDetails.getContent());
        String decodedSecretValue = new String(secretValueDecoded);
        return decodedSecretValue;
    }

    /**
     * Perform HTTP Post operation
     * 
     * This method will perform the following: - Transform the data using xslt
     * transofrmation - Convert XML to JSON. - Merge data - Create JSON document and
     * insert into JSON DB - Search and Update exisitng JSON document in the JSON DB
     * 
     * @param ctx   HTTP Context
     * @param input XML input payload
     * @return result as JSON string
     */
    private String processPostRequest(HTTPGatewayContext ctx, InputEvent input) {
        String result = "";
        String data = "";
        Long optyId = null;
        String inputData = input.consumeBody(is -> {
            try {
                return new StringBuilder(IOUtils.toString(is, StandardCharsets.UTF_8.toString())).toString();
            } catch (IOException e) {
                throw new IllegalArgumentException("Error reading input as string", e);
            }
        });
        JSONObject inputJSONObj = new JSONObject();
        String objName = "";
        List<String> docKeyList = null;
        try {
            inputData = transform(inputData);
            LOGGER.log(Level.INFO, "Transformed input payload");
            inputJSONObj = processOptyXML(inputData);
            if (inputJSONObj == null) {
                inputJSONObj = processCustomObjXML(inputData);
                objName = inputJSONObj.getString("Type_c");
                optyId = inputJSONObj.getLong("OptyId_c");
            } else {
                objName = "Opty";
                optyId = inputJSONObj.getLong("OptyId");
            }
        } catch (JSONException ex) {
            LOGGER.log(Level.SEVERE, "Unable to retrieve data");
            result = "{\"Error\": \"Error retriving data" + "\"}";
        }

        if (!objName.equalsIgnoreCase("Opty")) {
            LOGGER.log(Level.INFO, "This is a custom object Data");
            if (inputJSONObj != null) {
                Boolean success = createCustomObjJSON(objName, inputJSONObj);
                if (Boolean.FALSE.equals(success)) {
                    result = "<LoanData><Result>Failed</Result></LoanData>";
                    ctx.setStatusCode(404);
                } else {
                    result = "<LoanData><Result>OK</Result></LoanData>";
                    ctx.setStatusCode(202);
                }
            }

        } else {
            LOGGER.log(Level.INFO, "This is an Opty Data");
            docKeyList = getDocQueryByExample("{ \"OptyId\" : " + optyId + "}", db);
            if (inputJSONObj != null) {
                data = inputJSONObj.toString();
            }
            if (docKeyList.isEmpty()) {
                LOGGER.log(Level.INFO, "Creating New JSON Doc");
                createDocument(db, data);
            } else if (docKeyList.size() == 1) {
                for (String docKey : docKeyList) {
                    LOGGER.log(Level.INFO, "Updating Doc");
                    updateDocument(db, docKey, data, "Opty");
                }
            } else {
                LOGGER.log(Level.WARNING, "Error processing payload, more than 1 doc found");
            }
            result = "<LoanData><Result>OK</Result></LoanData>";
            ctx.setStatusCode(202);
            LOGGER.log(Level.INFO, "OUTPUT: {0}", result);
        }
        return result;
    }

    /**
     * Generate a UUID
     * 
     * @return UUID string
     */
    private String generateUUI() {
        UUID uuid = UUID.randomUUID();
        return uuid.toString();
    }

    /**
     * Perform HTTP Get operation.
     * 
     * Get the data from the JSON DB.
     * 
     * @param ctx
     * @return
     */
    private String processGetDataRequest(HTTPGatewayContext ctx) {
        String result = "";
        QueryParameters qp = ctx.getQueryParameters();
        String email = qp.getValues("email").get(0);
        String optyId = qp.getValues("loanid").get(0);
        String uuid = qp.getValues("code").get(0);
        List<String> docKeyList = getDocQueryByExample(
                "{ \"OptyId\" : " + optyId + ",\"EmailAddress\" : \"" + email + "\",\"uuid\" : \"" + uuid + "\"}", db);
        if (docKeyList.size() != 1) { // Expect only 1 document found if not return
            LOGGER.log(Level.INFO, "Search Document Error: You have 0 or more than one document in JSON DB");
            result = StringToJSON("No Application Found");
        } else {
            OracleDocument doc = getDocById(docKeyList.get(0)); // only get 1 document.
            try {
                if (doc != null) {
                    result = doc.getContentAsString();
                }
            } catch (OracleException e) {
                LOGGER.log(Level.SEVERE, "Unable to process GetData Request");
                result = "{\"Error\": \"Error retriving data: " + e.getMessage() + "\"}";
            }
        }
        return result;
    }

    /**
     * Transfrom xml payload using XSLT transformation
     * 
     * @param xml XML payload
     * @return Transformed data as string
     */
    private String transform(String xml) {
        StringWriter stringWriter = new StringWriter();
        try {
            DocumentBuilderFactory df = DocumentBuilderFactory.newInstance();
            df.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            df.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            df.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            df.setFeature("http://xml.org/sax/features/external-general-entities", false);
            df.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            df.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            df.setXIncludeAware(false);
            df.setExpandEntityReferences(false);
            DocumentBuilder builder = df.newDocumentBuilder();
            Document xmlDocument = builder.parse(new InputSource(new StringReader(xml)));

            Source stylesource = new StreamSource(new File("Xslt2Xml.xsl"));
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            Transformer transformer = factory.newTransformer(stylesource);
            transformer.transform(new DOMSource(xmlDocument), new StreamResult(stringWriter));
        } catch (TransformerConfigurationException e) {
            LOGGER.log(Level.SEVERE, "Error: XSLT Transformation Configuration");
        } catch (ParserConfigurationException ex) {
            LOGGER.log(Level.SEVERE, "Error: Parser Configuration");
        } catch (TransformerException te) {
            LOGGER.log(Level.SEVERE, "Error: Transformer Parser Configuration");
        } catch (SAXException se) {
            LOGGER.log(Level.SEVERE, "Error: SAX Exception");
        } catch (IOException ie) {
            LOGGER.log(Level.SEVERE, "Error: Unable to read xml file");
        }
        return stringWriter.toString();
    }

    /**
     * Convert Opportunity XML payload to JSON Object
     * 
     * @param inputData Opportunity XML data
     * @return Opportunity in JSON object
     */
    private JSONObject processOptyXML(String inputData) {
        JSONObject resultObj = null;
        try {
            XMLParserConfiguration config = new XMLParserConfiguration();
            config.withConvertNilAttributeToNull(true);
            resultObj = XML.toJSONObject(inputData, config).getJSONObject("Opty");
        } catch (JSONException e) {
            LOGGER.log(Level.SEVERE, "Unable to process Opty XML");
            return resultObj;
        }
        return resultObj;
    }

    /**
     * Convert custom object xml payload to JSON Object
     * 
     * @param inputData custom object xml payload
     * @return custom object json object
     */
    private JSONObject processCustomObjXML(String inputData) {
        XMLParserConfiguration config = new XMLParserConfiguration();
        config.withConvertNilAttributeToNull(true);
        return XML.toJSONObject(inputData, config).getJSONObject("LoanData");
    }

    /**
     * Find existing document and update the doc with the custom object json in JSON
     * DB
     * 
     * @param objName      The object name
     * @param inputJSONObj The custom object in json object
     * @return the json document in json db
     */
    private Boolean createCustomObjJSON(String objName, JSONObject inputJSONObj) {
        LOGGER.log(Level.INFO, "This is an {0} Custom Obj Data", objName);
        Boolean result = false;
        List<String> docKeyList = getDocQueryByExample("{ \"OptyId\" : " + inputJSONObj.getLong("OptyId_c") + "}", db);
        if (!docKeyList.isEmpty()) {
            LOGGER.log(Level.INFO, "Found exisiting Opty data, update or add new data");
            for (String docKey : docKeyList) {
                OracleDocument optyDoc = getDocById(docKey);
                try {
                    JSONObject currentJsonObj = new JSONObject(optyDoc.getContentAsString());
                    currentJsonObj.put(objName, inputJSONObj);
                    String newObj = currentJsonObj.toString();
                    updateDocument(db, docKey, newObj, "Custom");
                    result = true;
                } catch (OracleException e) {
                    LOGGER.log(Level.SEVERE, "Unable to create Custom Obj JSON");
                }
            }
        }
        return result;
    }

    /**
     * Create the JSON DB database connection
     * 
     * @return OracleDatabase
     */
    private OracleDatabase connectDB() {
        LOGGER.log(Level.INFO, "Database connecting");
        System.setProperty("oracle.soda.sharedMetadataCache", "true");
        System.setProperty("oracle.soda.localMetadataCache", "true");
        try {
            DriverManager.registerDriver(new oracle.jdbc.OracleDriver());
            con = DriverManager.getConnection(dbUrl, dbUser, dbPwd);
            OracleRDBMSClient cl = new OracleRDBMSClient();
            LOGGER.log(Level.INFO, "Database connected");
            db = cl.getDatabase(con);
        } catch (SQLException | OracleException e) {
            LOGGER.log(Level.SEVERE, "Error in DB Connection");
        }
        return db;
    }

    /**
     * Get JSON DB doc using Query By Example
     * 
     * @param searchStr Search String
     * @param db        OracleDatabase
     * @return List of Doc Keys
     */
    private List<String> getDocQueryByExample(String searchStr, OracleDatabase db) {
        // Create the filter specification
        OracleDocument filterSpec;
        List<String> docKeyList = new ArrayList<>();
        try {
            filterSpec = db.createDocumentFromString(searchStr);
            OracleCursor c = collection.find().filter(filterSpec).getCursor();
            while (c.hasNext()) {
                OracleDocument resultDoc = c.next();
                printDoc(resultDoc);
                docKeyList.add(resultDoc.getKey());
            }
            c.close();
        } catch (OracleException | IOException e) {
            LOGGER.log(Level.SEVERE, "Unable to get the document");
        }
        LOGGER.log(Level.INFO, "No. Doc Found : {0}", docKeyList.size());
        return docKeyList;
    }

    /**
     * Get exisitng DB collection in JSON DB
     * 
     * @param db             OracleDatabase
     * @param collectionName Name of the collection
     * @return Oracle Collection
     * @throws OracleException
     */
    private OracleCollection getExistingCollection(OracleDatabase db, String collectionName) throws OracleException {
        OracleCollection col = null;
        List<String> names = db.admin().getCollectionNames();
        if (!names.isEmpty()) {
            for (String name : names) {
                LOGGER.log(Level.INFO, "Found collection : {0}", name);
                if (name != null && name.equalsIgnoreCase(collectionName)) {
                    col = db.openCollection(collectionName);
                } else {
                    col = db.admin().createCollection(collectionName);
                }
            }
        } else {
            col = db.admin().createCollection(collectionName, metaDoc);
        }
        return col;
    }

    /**
     * Get JSON DB document by ID
     * 
     * @param id JSON DB Doc Id
     * @return OracleDocument
     */
    private OracleDocument getDocById(String id) {
        OracleDocument doc = null;
        try {
            doc = collection.find().key(id).getOne();
            if (doc != null) {
                printDoc(doc);
            } else {
                LOGGER.log(Level.INFO, "No document found");
            }
        } catch (OracleException e) {
            LOGGER.log(Level.SEVERE, "Unable to get the document");
        }
        return doc;
    }

    /**
     * Create Doc in JSON DB
     * 
     * @param db         OracleDatabase
     * @param jsonString JSON String
     */
    private void createDocument(OracleDatabase db, String jsonString) {
        try {
            OracleDocument doc = db.createDocumentFromString(jsonString);
            OracleDocument insertedDoc = collection.insertAndGet(doc);
            if (insertedDoc != null) {
                printDoc(insertedDoc);
            }
        } catch (OracleException e) {
            LOGGER.log(Level.SEVERE, "Unable to create the document");
        }
    }

    /**
     * Update exisiting doc in JSON DB
     * 
     * @param db         OracleDatabase
     * @param docKey     Document key
     * @param jsonString JSON String
     */
    private void updateDocument(OracleDatabase db, String docKey, String jsonString, String type) {
        try {
            LOGGER.log(Level.INFO, "Update the document : {0}", docKey);
            if (type.equalsIgnoreCase("Opty")) {
                OracleDocument currentDoc = getDocById(docKey);
                if (currentDoc != null) {
                    JSONObject currentJsonObj = new JSONObject(currentDoc.getContentAsString());
                    JSONObject newJsonObj = new JSONObject(jsonString);
                    jsonString = getAndMergeCustomObj(currentJsonObj, newJsonObj);
                }
            }

            OracleDocument doc = db.createDocumentFromString(docKey, jsonString);
            OracleDocument replacedDoc = collection.find().key(docKey).replaceOneAndGet(doc);
            if (replacedDoc != null) {
                printDoc(replacedDoc);
            }
        } catch (OracleException e) {
            LOGGER.log(Level.SEVERE, "Unable to update the document");
        }
    }

    /**
     * Get all custom object data in exisitng json doc, and merge it to the new json
     * doc.
     * 
     * @param currentJsonObj
     * @param newJsonObj
     * @return
     */
    private String getAndMergeCustomObj(JSONObject currentJsonObj, JSONObject newJsonObj) {
        String jsonString = "";
        try {
            JSONObject currentAssetObj = (JSONObject) currentJsonObj.get("Assets");
            JSONObject currentLiabilitiestObj = (JSONObject) currentJsonObj.get("Liabilities");
            JSONObject currentIncomeObj = (JSONObject) currentJsonObj.get("Income");
            newJsonObj.put("Assets", currentAssetObj);
            newJsonObj.put("Liabilities", currentLiabilitiestObj);
            newJsonObj.put("Income", currentIncomeObj);
            jsonString = newJsonObj.toString();
        } catch (JSONException ex) {
            LOGGER.log(Level.SEVERE, "Error getting custom obj data in current json doc");
        }
        return jsonString;
    }

    /**
     * Convert String to JSON
     * 
     * @param data String
     * @return JSON String
     */
    private String StringToJSON(String data) {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("result", data);
        return jsonObject.toString();
    }

    /**
     * Print JSON DB document in log
     * 
     * @param doc OracleDocument
     */
    private void printDoc(OracleDocument doc) {
        try {
            String key = doc.getKey();
            String createdOn = doc.getCreatedOn();
            String lastModified = doc.getLastModified();
            String version = doc.getVersion();
            String content = doc.getContentAsString();
            LOGGER.log(Level.INFO, "***Document***  {0} **********",
                    key + createdOn + lastModified + version + content);
        } catch (OracleException e) {
            LOGGER.log(Level.SEVERE, "Unable to print doc");
        }

    }

    /**
     * Check if it is number
     * 
     * @param strNum
     * @return
     */
    private boolean isNumeric(String strNum) {
        if (strNum == null) {
            return false;
        }
        return pattern.matcher(strNum).matches();
    }
}
