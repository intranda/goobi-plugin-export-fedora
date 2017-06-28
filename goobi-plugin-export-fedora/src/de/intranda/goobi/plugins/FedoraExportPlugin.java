package de.intranda.goobi.plugins;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;

import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.log4j.Logger;
import org.goobi.beans.Process;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IExportPlugin;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.Namespace;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.NIOFileUtils;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.ExportFileException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.helper.exceptions.UghHelperException;
import de.sub.goobi.metadaten.MetadatenHelper;
import de.sub.goobi.metadaten.MetadatenImagesHelper;
import de.sub.goobi.persistence.managers.MetadataManager;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DigitalDocument;
import ugh.dl.ExportFileformat;
import ugh.dl.Fileformat;
import ugh.dl.Prefs;
import ugh.dl.VirtualFileGroup;
import ugh.exceptions.DocStructHasNoTypeException;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.UGHException;
import ugh.exceptions.WriteException;

@PluginImplementation

public class FedoraExportPlugin implements IExportPlugin, IPlugin {

    private static final Logger log = Logger.getLogger(FedoraExportPlugin.class);

    private static final String PLUGIN_NAME = "FedoraExport";

    private String fedoraUrl = "http://localhost:8088/rest";

    private List<String> imageDataList = new ArrayList<>();
    private String rootUrl;

    @Override
    public PluginType getType() {
        return PluginType.Export;
    }

    @Override
    public String getTitle() {
        return PLUGIN_NAME;
    }

    @Override
    public void setExportFulltext(boolean exportFulltext) {
    }

    @Override
    public void setExportImages(boolean exportImages) {
    }

    @Override
    public boolean startExport(Process process) throws IOException, InterruptedException, DocStructHasNoTypeException, PreferencesException,
            WriteException, MetadataTypeNotAllowedException, ExportFileException, UghHelperException, ReadException, SwapException, DAOException,
            TypeNotAllowedForParentException {
        return startExport(process, process.getProjekt().getDmsImportRootPath());
    }

    /**
     * 
     * @param folder
     * @param process
     * @param destination
     * @param useVersioning
     */
    private void ingestData(String folder, Process process, String destination, boolean useVersioning) {
        String identifier = MetadataManager.getMetadataValue(process.getId(), "CatalogIDDigital");

        Client client = ClientBuilder.newClient();
        WebTarget fedoraBase = client.target(fedoraUrl);
        Response transactionResponse = fedoraBase.path("fcr:tx").request().post(null);
        if (transactionResponse.getStatus() < 400) {
            String transactionUrl = transactionResponse.getHeaderString("location");

            // Create the required container hierarchy
            if (!createContainerHieararchyForRecord(transactionUrl, identifier)) {
                return;
            }

            WebTarget ingestLocation = client.target(transactionUrl);
            try {
                rootUrl = transactionUrl;
                WebTarget recordUrl = ingestLocation.path("records").path(identifier);
                WebTarget mediaUrl = recordUrl.path("media");

                // Add images
                List<Path> filesToIngest = NIOFileUtils.listFiles(folder);
                for (Path file : filesToIngest) {
                    String fileUrl = addFileResource(file, mediaUrl.path(file.getFileName().toString()), useVersioning);
                    if (fileUrl != null) {
                        imageDataList.add(fileUrl.replaceAll(rootUrl, fedoraUrl));
                    }
                }

                // Create METS file in the process folder and add it to the repository 
                Path metsFile = createMetsFile(process, process.getProcessDataDirectory());
                addFileResource(metsFile, recordUrl.path(metsFile.getFileName().toString()), useVersioning);
                // Copy METS file to export destination
                Path exportMetsFile = Paths.get(destination);
                Files.copy(metsFile, exportMetsFile, StandardCopyOption.REPLACE_EXISTING);

                ingestLocation.path("fcr:tx").path("fcr:commit").request().post(null);
            } catch (IOException | UGHException | DAOException | InterruptedException | SwapException e) {
                log.error(e);
                ingestLocation.path("fcr:tx").path("fcr:rollback").request().post(null);
            }
        }
    }

    /**
     * 
     * @param file
     * @param target
     * @param useVersioning
     * @param
     * @return File location URL in Fedora
     * @throws IOException
     */
    private static String addFileResource(Path file, WebTarget target, boolean useVersioning) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("file may not be null");
        }
        if (target == null) {
            throw new IllegalArgumentException("target may not be null");
        }

        // Check resource existence
        boolean exists = false;
        Response response = target.request().get();
        switch (response.getStatus()) {
            case 200:
                exists = true;
                log.debug("Resource already exists: " + target.getUri().toURL().toString());
                break;
        }

        try (InputStream inputStream = new FileInputStream(file.toFile())) {
            String version = "version." + String.valueOf(System.currentTimeMillis());
            Entity<InputStream> fileEntity = Entity.entity(inputStream, Files.probeContentType(file));
            if (exists) {
                if (useVersioning) {
                    // Add new version
                    response = target.path("fcr:versions").request().header("Slug", version).header("Content-Disposition", "attachment; filename=\""
                            + file.getFileName().toString() + "\"").post(Entity.entity(inputStream, Files.probeContentType(file)));
                } else {
                    // Delete file and its tombstone
                    response = target.request().delete();
                    response = target.path("fcr:tombstone").request().delete();
                    // Add file again
                    if (response.getStatus() == 204) {
                        response = target.request().header("Content-Disposition", "attachment; filename=\"" + file.getFileName().toString() + "\"")
                                .put(fileEntity);
                    } else {
                        String body = response.readEntity(String.class);
                        log.error(response.getStatus() + ": " + response.getStatusInfo().getReasonPhrase() + " - " + body);
                        throw new IOException(response.getStatusInfo().getReasonPhrase());
                    }
                }
            } else {
                // Add new file
                response = target.request().header("Content-Disposition", "attachment; filename=\"" + file.getFileName().toString() + "\"").put(
                        fileEntity);
            }
            switch (response.getStatus()) {
                case 201:
                    if (exists) {
                        if (useVersioning) {
                            log.debug("New resource version " + version + " added: " + response.getHeaderString("location"));
                        } else {
                            log.debug("Resource updated: " + response.getHeaderString("location"));
                        }
                    } else {
                        log.debug("New resource added: " + response.getHeaderString("location"));
                    }
                    break;
                default:
                    String body = response.readEntity(String.class);
                    log.error(response.getStatus() + ": " + response.getStatusInfo().getReasonPhrase() + " - " + body);
                    break;
            }

            return response.getHeaderString("location");
        }

    }

    /**
     * 
     * @param rootUrl
     * @param identifier
     * @return
     */
    private static boolean createContainerHieararchyForRecord(String rootUrl, String identifier) {
        String recordUrl = rootUrl + "/" + identifier;

        // Using Apache client because it supports PUT without an entity
        try (CloseableHttpClient httpClient = HttpClients.createMinimal()) {
            {
                // Create proper (non-pairtree) container for the record identifier
                HttpPut put = new HttpPut(recordUrl);
                try (CloseableHttpResponse httpResponse = httpClient.execute(put); StringWriter writer = new StringWriter()) {
                    switch (httpResponse.getStatusLine().getStatusCode()) {
                        case 201:
                            log.info("Container created: " + recordUrl);
                            break;
                        case 204:
                        case 409:
                            log.trace("Container already exists: " + recordUrl);
                            break;
                        default:
                            String body = IOUtils.toString(httpResponse.getEntity().getContent(), "UTF-8");
                            log.error(httpResponse.getStatusLine().getStatusCode() + ": " + httpResponse.getStatusLine().getReasonPhrase() + " - "
                                    + body);
                            return false;
                    }
                }
            }
            {
                // Create proper (non-pairtree) container for the media
                HttpPut put = new HttpPut(recordUrl + "/media");
                try (CloseableHttpResponse httpResponse = httpClient.execute(put); StringWriter writer = new StringWriter()) {
                    switch (httpResponse.getStatusLine().getStatusCode()) {
                        case 201:
                            log.info("Container created: " + recordUrl);
                            break;
                        case 204:
                        case 409:
                            log.trace("Container already exists: " + recordUrl);
                            break;
                        default:
                            String body = IOUtils.toString(httpResponse.getEntity().getContent(), "UTF-8");
                            log.error(httpResponse.getStatusLine().getStatusCode() + ": " + httpResponse.getStatusLine().getReasonPhrase() + " - "
                                    + body);
                            return false;
                    }
                }
            }
        } catch (IOException e) {
            log.error(e.getMessage(), e);
            return false;
        }

        return true;
    }

    private Path createMetsFile(Process process, String destination) throws UGHException, DAOException, InterruptedException, IOException,
            SwapException {
        Prefs prefs = process.getRegelsatz().getPreferences();
        Fileformat fileformat = process.readMetadataFile();

        ExportFileformat mm = MetadatenHelper.getExportFileformatByName(process.getProjekt().getFileFormatDmsExport(), process.getRegelsatz());
        mm.setWriteLocal(false);

        DigitalDocument dd = fileformat.getDigitalDocument();

        MetadatenImagesHelper mih = new MetadatenImagesHelper(prefs, dd);

        if (dd.getFileSet() == null || dd.getFileSet().getAllFiles().isEmpty()) {
            Helper.setMeldung(process.getTitel() + ": digital document does not contain images; temporarily adding them for mets file creation");
            mih.createPagination(process, null);
        } else {
            mih.checkImageNames(process);
        }

        mm.setDigitalDocument(dd);

        VariableReplacer vp = new VariableReplacer(mm.getDigitalDocument(), prefs, process, null);

        VirtualFileGroup v = new VirtualFileGroup();
        v.setName("FEDORA");
        v.setPathToFiles(rootUrl);
        v.setMimetype("image/html-sandboxed");
        v.setFileSuffix("tif");
        mm.getDigitalDocument().getFileSet().addVirtualFileGroup(v);

        // Replace rights and digiprov entries.
        mm.setRightsOwner(vp.replace(process.getProjekt().getMetsRightsOwner()));
        mm.setRightsOwnerLogo(vp.replace(process.getProjekt().getMetsRightsOwnerLogo()));
        mm.setRightsOwnerSiteURL(vp.replace(process.getProjekt().getMetsRightsOwnerSite()));
        mm.setRightsOwnerContact(vp.replace(process.getProjekt().getMetsRightsOwnerMail()));
        mm.setDigiprovPresentation(vp.replace(process.getProjekt().getMetsDigiprovPresentation()));
        mm.setDigiprovReference(vp.replace(process.getProjekt().getMetsDigiprovReference()));
        mm.setDigiprovPresentationAnchor(vp.replace(process.getProjekt().getMetsDigiprovPresentationAnchor()));
        mm.setDigiprovReferenceAnchor(vp.replace(process.getProjekt().getMetsDigiprovReferenceAnchor()));

        mm.setMetsRightsLicense(vp.replace(process.getProjekt().getMetsRightsLicense()));
        mm.setMetsRightsSponsor(vp.replace(process.getProjekt().getMetsRightsSponsor()));
        mm.setMetsRightsSponsorLogo(vp.replace(process.getProjekt().getMetsRightsSponsorLogo()));
        mm.setMetsRightsSponsorSiteURL(vp.replace(process.getProjekt().getMetsRightsSponsorSiteURL()));

        mm.setPurlUrl(vp.replace(process.getProjekt().getMetsPurl()));
        mm.setContentIDs(vp.replace(process.getProjekt().getMetsContentIDs()));

        String pointer = process.getProjekt().getMetsPointerPath();
        pointer = vp.replace(pointer);
        mm.setMptrUrl(pointer);

        String anchor = process.getProjekt().getMetsPointerPathAnchor();
        pointer = vp.replace(anchor);
        mm.setMptrAnchorUrl(pointer);

        mm.setGoobiID(String.valueOf(process.getId()));
        Path tempFile = Files.createTempFile(process.getTitel(), ".xml");

        mm.write(tempFile.toString());

        overwriteUrls(tempFile.toString());
        Path metsFilePath = Paths.get(destination, process.getTitel() + ".xml");
        Files.copy(tempFile, metsFilePath, NIOFileUtils.STANDARD_COPY_OPTIONS);
        return metsFilePath;
    }

    @Override
    public boolean startExport(Process process, String destination) throws IOException, InterruptedException, DocStructHasNoTypeException,
            PreferencesException, WriteException, MetadataTypeNotAllowedException, ExportFileException, UghHelperException, ReadException,
            SwapException, DAOException, TypeNotAllowedForParentException {
        fedoraUrl = process.getProjekt().getDmsImportImagesPath();
        Path imageFolder = Paths.get(process.getImagesTifDirectory(true));
        ingestData(imageFolder.toString(), process, destination, false);

        Helper.setMeldung(null, process.getTitel() + ": ", "ExportFinished");

        return true;
    }

    private void overwriteUrls(String metsfile) {
        Namespace mets = Namespace.getNamespace("mets", "http://www.loc.gov/METS/");
        Namespace xlink = Namespace.getNamespace("xlink", "http://www.w3.org/1999/xlink");
        SAXBuilder parser = new SAXBuilder();
        try {
            Document metsDoc = parser.build(metsfile);
            Element fileSec = metsDoc.getRootElement().getChild("fileSec", mets);

            for (Element fileGrp : fileSec.getChildren()) {
                if (fileGrp.getAttributeValue("USE").equals("FEDORA")) {
                    List<Element> fileList = fileGrp.getChildren();
                    for (int i = 0; i < fileList.size(); i++) {
                        Element file = fileList.get(i);
                        Element flocat = file.getChild("FLocat", mets);
                        flocat.setAttribute("href", imageDataList.get(i), xlink);
                    }
                }
            }
            XMLOutputter outputter = new XMLOutputter(Format.getPrettyFormat());
            FileOutputStream output = new FileOutputStream(metsfile);
            outputter.output(metsDoc, output);
        } catch (JDOMException | IOException e) {
            log.error(e);
        }

    }

    public String getDescription() {
        return getTitle();
    }

    @Override
    public List<String> getProblems() {
        // TODO Auto-generated method stub
        return null;
    }
}
