package edu.harvard.iq.dataverse;

import edu.harvard.iq.dataverse.settings.SettingsServiceBean;
import edu.harvard.iq.dataverse.util.SystemConfig;
import java.io.InputStream;

import javax.inject.Inject;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang3.RandomStringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public abstract class AbstractGlobalIdServiceBean implements GlobalIdServiceBean {

    private static final Logger logger = Logger.getLogger(AbstractGlobalIdServiceBean.class.getCanonicalName());

    @Inject
    DataverseServiceBean dataverseService;
    @Inject
    SettingsServiceBean settingsService;
    @Inject
    DvObjectServiceBean dvObjectService;
    @Inject
    SystemConfig systemConfig;
    
    public static String UNAVAILABLE = ":unav";

    @Override
    public Map<String, String> getMetadataForCreateIndicator(DvObject dvObjectIn) {
        logger.log(Level.FINE,"getMetadataForCreateIndicator(DvObject)");
        Map<String, String> metadata = new HashMap<>();
        metadata = addBasicMetadata(dvObjectIn, metadata);
        metadata.put("datacite.publicationyear", generateYear(dvObjectIn));
        metadata.put("_target", getTargetUrl(dvObjectIn));
        return metadata;
    }

    protected Map<String, String> getUpdateMetadata(DvObject dvObjectIn) {
        logger.log(Level.FINE,"getUpdateMetadataFromDataset");
        Map<String, String> metadata = new HashMap<>();
        metadata = addBasicMetadata(dvObjectIn, metadata);
        return metadata;
    }
    
    protected Map<String, String> addBasicMetadata(DvObject dvObjectIn, Map<String, String> metadata) {

        String authorString = dvObjectIn.getAuthorString();
        if (authorString.isEmpty() || authorString.contains(DatasetField.NA_VALUE)) {
            authorString = UNAVAILABLE;
        }

        String producerString = dataverseService.getRootDataverseName();

        if (producerString.isEmpty() || producerString.equals(DatasetField.NA_VALUE)) {
            producerString = UNAVAILABLE;
        }

        String titleString = dvObjectIn.getCurrentName();

        if (titleString.isEmpty() || titleString.equals(DatasetField.NA_VALUE)) {
            titleString = UNAVAILABLE;
        }

        metadata.put("datacite.creator", authorString);
        metadata.put("datacite.title", titleString);
        metadata.put("datacite.publisher", producerString);
        metadata.put("datacite.publicationyear", generateYear(dvObjectIn));
        return metadata;
    } 
    
    protected Map<String, String> addDOIMetadataForDestroyedDataset(DvObject dvObjectIn) {
        Map<String, String> metadata = new HashMap<>();
        String authorString = UNAVAILABLE;
        String producerString = UNAVAILABLE;
        String titleString = "This item has been removed from publication";

        metadata.put("datacite.creator", authorString);
        metadata.put("datacite.title", titleString);
        metadata.put("datacite.publisher", producerString);
        metadata.put("datacite.publicationyear", "9999");
        return metadata;
    } 

    protected String getTargetUrl(DvObject dvObjectIn) {
        logger.log(Level.FINE,"getTargetUrl");
        return systemConfig.getDataverseSiteUrl() + dvObjectIn.getTargetUrl() + dvObjectIn.getGlobalId().asString();
    }
    
    @Override
    public String getIdentifier(DvObject dvObject) {
        return dvObject.getGlobalId().asString();
    }

    protected String generateYear (DvObject dvObjectIn){
        return dvObjectIn.getYearPublishedCreated(); 
    }
    
    public Map<String, String> getMetadataForTargetURL(DvObject dvObject) {
        logger.log(Level.FINE,"getMetadataForTargetURL");
        HashMap<String, String> metadata = new HashMap<>();
        metadata.put("_target", getTargetUrl(dvObject));
        return metadata;
    }
    
    @Override
    public boolean alreadyExists(DvObject dvo) throws Exception {
        return alreadyExists(dvo.getGlobalId());
    }

    /*
     * ToDo: the DvObject being sent in provides partial support for the case where
     * it has a different authority/protocol than what is configured (i.e. a legacy
     * Pid that can actually be updated by the Pid account being used.) Removing
     * this now would potentially break/make it harder to handle that case prior to
     * support for configuring multiple Pid providers. Once that exists, it would be
     * cleaner to always find the PidProvider associated with the
     * protocol/authority/shoulder of the current dataset and then not pass the
     * DvObject as a param. (This would also remove calls to get the settings since
     * that would be done at construction.)
     */
    @Override
    public DvObject generateIdentifier(DvObject dvObject) {

        String protocol = dvObject.getProtocol() == null ? settingsService.getValueForKey(SettingsServiceBean.Key.Protocol) : dvObject.getProtocol();
        String authority = dvObject.getAuthority() == null ? settingsService.getValueForKey(SettingsServiceBean.Key.Authority) : dvObject.getAuthority();
        if (dvObject.isInstanceofDataset()) {
            dvObject.setIdentifier(generateDatasetIdentifier((Dataset) dvObject));
        } else {
            dvObject.setIdentifier(generateDataFileIdentifier((DataFile) dvObject));
        }
        if (dvObject.getProtocol() == null) {
            dvObject.setProtocol(protocol);
        }
        if (dvObject.getAuthority() == null) {
            dvObject.setAuthority(authority);
        }
        return dvObject;
    }
    
    //ToDo just send the DvObject.DType
    public String generateDatasetIdentifier(Dataset dataset) {
        //ToDo - track these in the bean
        String identifierType = settingsService.getValueForKey(SettingsServiceBean.Key.IdentifierGenerationStyle, "randomString");
        String shoulder = settingsService.getValueForKey(SettingsServiceBean.Key.Shoulder, "");

        switch (identifierType) {
            case "randomString":
                return generateIdentifierAsRandomString(dataset, shoulder);
            case "storedProcGenerated":
                return generateIdentifierFromStoredProcedureIndependent(dataset, shoulder);
            default:
                /* Should we throw an exception instead?? -- L.A. 4.6.2 */
                return generateIdentifierAsRandomString(dataset, shoulder);
        }
    }


    /**
     * Check that a identifier entered by the user is unique (not currently used
     * for any other study in this Dataverse Network) also check for duplicate
     * in EZID if needed
     * @param userIdentifier
     * @param dataset
     * @return {@code true} if the identifier is unique, {@code false} otherwise.
     */
    public boolean isGlobalIdUnique(GlobalId globalId) {
        if ( ! dvObjectService.isGlobalIdLocallyUnique(globalId)  ) {
            return false; // duplication found in local database
        }

        // not in local DB, look in the persistent identifier service
        try {
            return ! alreadyExists(globalId);
        } catch (Exception e){
            //we can live with failure - means identifier not found remotely
        }

        return true;
    }

    @Override
    public GlobalId parsePersistentId(String identifierString) {
        String protocol;
        String authority;
        String identifier;
        if (identifierString == null) {
            return null;
        }
        int index1 = identifierString.indexOf(':');
        if (index1 > 0) { // ':' found with one or more characters before it
            int index2 = identifierString.indexOf('/', index1 + 1);
            if (index2 > 0 && (index2 + 1) < identifierString.length()) { // '/' found with one or more characters
                                                                          // between ':'
                protocol = identifierString.substring(0, index1); // and '/' and there are characters after '/'
                //If not in the set of available protocols
                if (!DOI_PROTOCOL.equals(protocol) && !HDL_PROTOCOL.equals(protocol) && !PermaLinkPidProviderServiceBean.PERMA_PROTOCOL.equals(protocol)) {
                    return null;
                }
                //Strip any whitespace, ; and ' from authority (should finding them cause a failure instead?)
                authority = GlobalIdServiceBean.formatIdentifierString(identifierString.substring(index1 + 1, index2));
                if(GlobalIdServiceBean.testforNullTerminator(authority)) return null;
                if (protocol.equals(DOI_PROTOCOL)) {
                    if (!GlobalIdServiceBean.checkDOIAuthority(authority)) {
                        return null;
                    }
                }
                // Passed all checks
                //Strip any whitespace, ; and ' from identifier (should finding them cause a failure instead? - Yes!)
                identifier = GlobalIdServiceBean.formatIdentifierString(identifierString.substring(index2 + 1));
                if(GlobalIdServiceBean.testforNullTerminator(identifier)) return null;
            } else {
                logger.log(Level.INFO, "Error parsing identifier: {0}: '':<authority>/<identifier>'' not found in string", identifierString);
                return null;
            }
        } else {
            logger.log(Level.INFO, "Error parsing identifier: {0}: ''<protocol>:'' not found in string", identifierString);
            return null;
        }
        return new GlobalId(protocol, authority, identifier);
    }
    
    @Override
    public String generateDataFileIdentifier(DataFile datafile) {
        String doiIdentifierType = settingsService.getValueForKey(SettingsServiceBean.Key.IdentifierGenerationStyle, "randomString");
        String doiDataFileFormat = settingsService.getValueForKey(SettingsServiceBean.Key.DataFilePIDFormat, SystemConfig.DataFilePIDFormat.DEPENDENT.toString());

        String prepend = "";
        if (doiDataFileFormat.equals(SystemConfig.DataFilePIDFormat.DEPENDENT.toString())){
            //If format is dependent then pre-pend the dataset identifier 
            prepend = datafile.getOwner().getIdentifier() + "/";
        } else {
            //If there's a shoulder prepend independent identifiers with it
            prepend = settingsService.getValueForKey(SettingsServiceBean.Key.Shoulder, "");
        }
 
        switch (doiIdentifierType) {
            case "randomString":               
                return generateIdentifierAsRandomString(datafile, prepend);
            case "storedProcGenerated":
                if (doiDataFileFormat.equals(SystemConfig.DataFilePIDFormat.INDEPENDENT.toString())){ 
                    return generateIdentifierFromStoredProcedureIndependent(datafile, prepend);
                } else {
                    return generateIdentifierFromStoredProcedureDependent(datafile, prepend);
                }
            default:
                /* Should we throw an exception instead?? -- L.A. 4.6.2 */
                return generateIdentifierAsRandomString(datafile, prepend);
        }
    }
    

    /*
     * This method checks locally for a DvObject with the same PID and if that is OK, checks with the PID service.
     * @param dvo - the object to check (ToDo - get protocol/authority from this PidProvider object)
     * @param prepend - for Datasets, this is always the shoulder, for DataFiles, it could be the shoulder or the parent Dataset identifier
     */
    private String generateIdentifierAsRandomString(DvObject dvo, String prepend) {
        String identifier = null;
        do {
            identifier = prepend + RandomStringUtils.randomAlphanumeric(6).toUpperCase();  
        } while (!isGlobalIdUnique(new GlobalId(dvo.getProtocol(), dvo.getAuthority(), identifier)));

        return identifier;
    }

    /*
     * This method checks locally for a DvObject with the same PID and if that is OK, checks with the PID service.
     * @param dvo - the object to check (ToDo - get protocol/authority from this PidProvider object)
     * @param prepend - for Datasets, this is always the shoulder, for DataFiles, it could be the shoulder or the parent Dataset identifier
     */

    private String generateIdentifierFromStoredProcedureIndependent(DvObject dvo, String prepend) {
        String identifier; 
        do {
            String identifierFromStoredProcedure = dvObjectService.generateNewIdentifierByStoredProcedure();
            // some diagnostics here maybe - is it possible to determine that it's failing 
            // because the stored procedure hasn't been created in the database?
            if (identifierFromStoredProcedure == null) {
                return null; 
            }
            identifier = prepend + identifierFromStoredProcedure;
        } while (!isGlobalIdUnique(new GlobalId(dvo.getProtocol(), dvo.getAuthority(), identifier)));
        
        return identifier;
    }
    
    /*This method is only used for DataFiles with DEPENDENT Pids. It is not for Datasets
     * 
     */
    private String generateIdentifierFromStoredProcedureDependent(DataFile datafile, String prepend) {
        String identifier;
        Long retVal;
        //ToDo - replace loop with one DB lookup for largest entry? (adding 1000 files, this loop would run ~n**2/2 db calls)
        retVal = Long.valueOf(0L);

        do {
            retVal++;
            identifier = prepend + retVal.toString();

        } while (!isGlobalIdUnique(new GlobalId(datafile.getProtocol(), datafile.getAuthority(), identifier)));

        return identifier;
    }

    
    class GlobalIdMetadataTemplate {


    private   String template;

    public GlobalIdMetadataTemplate(){
        try (InputStream in = GlobalIdMetadataTemplate.class.getResourceAsStream("datacite_metadata_template.xml")) {
            template = Util.readAndClose(in, "utf-8");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "datacite metadata template load error");
            logger.log(Level.SEVERE, "String " + e.toString());
            logger.log(Level.SEVERE, "localized message " + e.getLocalizedMessage());
            logger.log(Level.SEVERE, "cause " + e.getCause());
            logger.log(Level.SEVERE, "message " + e.getMessage());
        }
    }

    private String xmlMetadata;
    private String identifier;
    private List<String> datafileIdentifiers;
    private List<String> creators;
    private String title;
    private String publisher;
    private String publisherYear;
    private List<DatasetAuthor> authors;
    private String description;
    private List<String[]> contacts;
    private List<String[]> producers;

    public List<String[]> getProducers() {
        return producers;
    }

    public void setProducers(List<String[]> producers) {
        this.producers = producers;
    }

    public List<String[]> getContacts() {
        return contacts;
    }

    public void setContacts(List<String[]> contacts) {
        this.contacts = contacts;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<DatasetAuthor> getAuthors() {
        return authors;
    }

    public void setAuthors(List<DatasetAuthor> authors) {
        this.authors = authors;
    }


    public List<String> getDatafileIdentifiers() {
        return datafileIdentifiers;
    }

    public void setDatafileIdentifiers(List<String> datafileIdentifiers) {
        this.datafileIdentifiers = datafileIdentifiers;
    }

    public GlobalIdMetadataTemplate(String xmlMetaData) {
        this.xmlMetadata = xmlMetaData;
        Document doc = Jsoup.parseBodyFragment(xmlMetaData);
        Elements identifierElements = doc.select("identifier");
        if (identifierElements.size() > 0) {
            identifier = identifierElements.get(0).html();
        }
        Elements creatorElements = doc.select("creatorName");
        creators = new ArrayList<>();
        for (Element creatorElement : creatorElements) {
            creators.add(creatorElement.html());
        }
        Elements titleElements = doc.select("title");
        if (titleElements.size() > 0) {
            title = titleElements.get(0).html();
        }
        Elements publisherElements = doc.select("publisher");
        if (publisherElements.size() > 0) {
            publisher = publisherElements.get(0).html();
        }
        Elements publisherYearElements = doc.select("publicationYear");
        if (publisherYearElements.size() > 0) {
            publisherYear = publisherYearElements.get(0).html();
        }
    }

    public String generateXML(DvObject dvObject) {
        // Can't use "UNKNOWN" here because DataCite will respond with "[facet 'pattern'] the value 'unknown' is not accepted by the pattern '[\d]{4}'"
        String publisherYearFinal = "9999";
        // FIXME: Investigate why this.publisherYear is sometimes null now that pull request #4606 has been merged.
        if (this.publisherYear != null) {
            // Added to prevent a NullPointerException when trying to destroy datasets when using DataCite rather than EZID.
            publisherYearFinal = this.publisherYear;
        }
        xmlMetadata = template.replace("${identifier}", getIdentifier().trim())
                .replace("${title}", this.title)
                .replace("${publisher}", this.publisher)
                .replace("${publisherYear}", publisherYearFinal)
                .replace("${description}", this.description);
        StringBuilder creatorsElement = new StringBuilder();
        for (DatasetAuthor author : authors) {
            creatorsElement.append("<creator><creatorName>");
            creatorsElement.append(author.getName().getDisplayValue());
            creatorsElement.append("</creatorName>");

            if (author.getIdType() != null && author.getIdValue() != null && !author.getIdType().isEmpty() && !author.getIdValue().isEmpty() && author.getAffiliation() != null && !author.getAffiliation().getDisplayValue().isEmpty()) {

                if (author.getIdType().equals("ORCID")) {
                    creatorsElement.append("<nameIdentifier schemeURI=\"https://orcid.org/\" nameIdentifierScheme=\"ORCID\">" + author.getIdValue() + "</nameIdentifier>");
                }
                if (author.getIdType().equals("ISNI")) {
                    creatorsElement.append("<nameIdentifier schemeURI=\"http://isni.org/isni/\" nameIdentifierScheme=\"ISNI\">" + author.getIdValue() + "</nameIdentifier>");
                }
                if (author.getIdType().equals("LCNA")) {
                    creatorsElement.append("<nameIdentifier schemeURI=\"http://id.loc.gov/authorities/names/\" nameIdentifierScheme=\"LCNA\">" + author.getIdValue() + "</nameIdentifier>");
                }
            }
            if (author.getAffiliation() != null && !author.getAffiliation().getDisplayValue().isEmpty()) {
                creatorsElement.append("<affiliation>" + author.getAffiliation().getDisplayValue() + "</affiliation>");
            }
            creatorsElement.append("</creator>");
        }
        xmlMetadata = xmlMetadata.replace("${creators}", creatorsElement.toString());

        StringBuilder contributorsElement = new StringBuilder();
        for (String[] contact : this.getContacts()) {
            if (!contact[0].isEmpty()) {
                contributorsElement.append("<contributor contributorType=\"ContactPerson\"><contributorName>" + contact[0] + "</contributorName>");
                if (!contact[1].isEmpty()) {
                    contributorsElement.append("<affiliation>" + contact[1] + "</affiliation>");
                }
                contributorsElement.append("</contributor>");
            }
        }
        for (String[] producer : this.getProducers()) {
            contributorsElement.append("<contributor contributorType=\"Producer\"><contributorName>" + producer[0] + "</contributorName>");
            if (!producer[1].isEmpty()) {
                contributorsElement.append("<affiliation>" + producer[1] + "</affiliation>");
            }
            contributorsElement.append("</contributor>");
        }

        String relIdentifiers = generateRelatedIdentifiers(dvObject);

        xmlMetadata = xmlMetadata.replace("${relatedIdentifiers}", relIdentifiers);

        xmlMetadata = xmlMetadata.replace("{$contributors}", contributorsElement.toString());
        return xmlMetadata;
    }

    private String generateRelatedIdentifiers(DvObject dvObject) {

        StringBuilder sb = new StringBuilder();
        if (dvObject.isInstanceofDataset()) {
            Dataset dataset = (Dataset) dvObject;
            if (!dataset.getFiles().isEmpty() && !(dataset.getFiles().get(0).getIdentifier() == null)) {

                datafileIdentifiers = new ArrayList<>();
                for (DataFile dataFile : dataset.getFiles()) {
                    if (!dataFile.getGlobalId().asString().isEmpty()) {
                        if (sb.toString().isEmpty()) {
                            sb.append("<relatedIdentifiers>");
                        }
                        sb.append("<relatedIdentifier relatedIdentifierType=\"DOI\" relationType=\"HasPart\">" + dataFile.getGlobalId() + "</relatedIdentifier>");
                    }
                }

                if (!sb.toString().isEmpty()) {
                    sb.append("</relatedIdentifiers>");
                }
            }
        } else if (dvObject.isInstanceofDataFile()) {
            DataFile df = (DataFile) dvObject;
            sb.append("<relatedIdentifiers>");
            sb.append("<relatedIdentifier relatedIdentifierType=\"DOI\" relationType=\"IsPartOf\""
                    + ">" + df.getOwner().getGlobalId() + "</relatedIdentifier>");
            sb.append("</relatedIdentifiers>");
        }
        return sb.toString();
    }

    public void generateFileIdentifiers(DvObject dvObject) {

        if (dvObject.isInstanceofDataset()) {
            Dataset dataset = (Dataset) dvObject;

            if (!dataset.getFiles().isEmpty() && !(dataset.getFiles().get(0).getIdentifier() == null)) {

                datafileIdentifiers = new ArrayList<>();
                for (DataFile dataFile : dataset.getFiles()) {
                    datafileIdentifiers.add(dataFile.getIdentifier());
                    int x = xmlMetadata.indexOf("</relatedIdentifiers>") - 1;
                    xmlMetadata = xmlMetadata.replace("{relatedIdentifier}", dataFile.getIdentifier());
                    xmlMetadata = xmlMetadata.substring(0, x) + "<relatedIdentifier relatedIdentifierType=\"hasPart\" "
                            + "relationType=\"doi\">${relatedIdentifier}</relatedIdentifier>" + template.substring(x, template.length() - 1);

                }

            } else {
                xmlMetadata = xmlMetadata.replace("<relatedIdentifier relatedIdentifierType=\"hasPart\" relationType=\"doi\">${relatedIdentifier}</relatedIdentifier>", "");
            }
        }
    }

    public  String getTemplate() {
        return template;
    }

    public  void setTemplate(String templateIn) {
        template = templateIn;
    }

    public String getIdentifier() {
        return identifier;
    }

    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    public List<String> getCreators() {
        return creators;
    }

    public void setCreators(List<String> creators) {
        this.creators = creators;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getPublisher() {
        return publisher;
    }

    public void setPublisher(String publisher) {
        this.publisher = publisher;
    }

    public String getPublisherYear() {
        return publisherYear;
    }

    public void setPublisherYear(String publisherYear) {
        this.publisherYear = publisherYear;
    }
}
    public String getMetadataFromDvObject(String identifier, Map<String, String> metadata, DvObject dvObject) {

        Dataset dataset = null;

        if (dvObject instanceof Dataset) {
            dataset = (Dataset) dvObject;
        } else {
            dataset = (Dataset) dvObject.getOwner();
        }

        GlobalIdMetadataTemplate metadataTemplate = new GlobalIdMetadataTemplate();
        metadataTemplate.setIdentifier(identifier.substring(identifier.indexOf(':') + 1));
        metadataTemplate.setCreators(Util.getListFromStr(metadata.get("datacite.creator")));
        metadataTemplate.setAuthors(dataset.getLatestVersion().getDatasetAuthors());
        if (dvObject.isInstanceofDataset()) {
            metadataTemplate.setDescription(dataset.getLatestVersion().getDescriptionPlainText());
        }
        if (dvObject.isInstanceofDataFile()) {
            DataFile df = (DataFile) dvObject;
            String fileDescription = df.getDescription();
            metadataTemplate.setDescription(fileDescription == null ? "" : fileDescription);
        }

        metadataTemplate.setContacts(dataset.getLatestVersion().getDatasetContacts());
        metadataTemplate.setProducers(dataset.getLatestVersion().getDatasetProducers());
        metadataTemplate.setTitle(dvObject.getCurrentName());
        String producerString = dataverseService.getRootDataverseName();
        if (producerString.isEmpty()  || producerString.equals(DatasetField.NA_VALUE) ) {
            producerString = UNAVAILABLE;
        }
        metadataTemplate.setPublisher(producerString);
        metadataTemplate.setPublisherYear(metadata.get("datacite.publicationyear"));

        String xmlMetadata = metadataTemplate.generateXML(dvObject);
        logger.log(Level.FINE, "XML to send to DataCite: {0}", xmlMetadata);
        return xmlMetadata;
    }
    
}
