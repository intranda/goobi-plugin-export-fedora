package de.intranda.goobi.plugins;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;

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
import ugh.exceptions.WriteException;

@PluginImplementation

public class FedoraExportPlugin implements IExportPlugin, IPlugin {

    private static final Logger log = Logger.getLogger(FedoraExportPlugin.class);

    private static final String PLUGIN_NAME = "FedoraExport";

    private String fedoraUrl = "http://localhost:8081/rest";

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

    private void ingestData(String folder) {
        Client client = ClientBuilder.newClient();
        WebTarget fedoraBase = client.target(fedoraUrl);
        WebTarget ingest = fedoraBase.path("fcr:tx");
        Response response = ingest.request().post(null);
        if (response.getStatus() < 400) {
            String location = response.getHeaderString("location");
            WebTarget ingestLocation = client.target(location);
            rootUrl = location;
            List<Path> filesToIngest = NIOFileUtils.listFiles(folder);
            for (Path file : filesToIngest) {
                try {
                    InputStream inputStream = new FileInputStream(file.toFile());
                    Entity<InputStream> fileEntity = Entity.entity(inputStream, Files.probeContentType(file));
                    Response fileIngestResponse = ingestLocation.request().header("filename", file.getFileName().toString()).post(fileEntity);
                    imageDataList.add(fileIngestResponse.getHeaderString("location"));
                } catch ( IOException e) {
                    log.error(e);
                }
            }
        }
    }

    @Override
    public boolean startExport(Process process, String destination) throws IOException, InterruptedException, DocStructHasNoTypeException,
            PreferencesException, WriteException, MetadataTypeNotAllowedException, ExportFileException, UghHelperException, ReadException,
            SwapException, DAOException, TypeNotAllowedForParentException {
        fedoraUrl =  process.getProjekt().getDmsImportImagesPath();
        Prefs prefs = process.getRegelsatz().getPreferences();
        Fileformat fileformat = process.readMetadataFile();

        Path imageFolder = Paths.get(process.getImagesTifDirectory(true));
        ingestData(imageFolder.toString());

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

        Files.copy(tempFile, Paths.get(destination, process.getTitel() + ".xml"), NIOFileUtils.STANDARD_COPY_OPTIONS);

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
    
}
