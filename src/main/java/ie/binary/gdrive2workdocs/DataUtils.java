package ie.binary.gdrive2workdocs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import org.apache.commons.io.FilenameUtils;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.yaml.snakeyaml.Yaml;

import static com.amazonaws.util.StringUtils.isNullOrEmpty;
import static java.lang.System.getProperty;
import static java.lang.System.getenv;
import static org.apache.commons.io.FileUtils.readFileToString;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

public class DataUtils {


    public static final String DELIMITER;

    public static String getExtensionSuffix(String contentType) {
        return mimeTypeExtensions.get(contentType);
    }

    public static String getContentTypeByExtension(String ext) {
        return mimeTypeExtensions.inverse().get(ext);
    }

    public static Collection<String> getWorkdocNames() {
        return workdocs.keySet();
    }

    public static Collection<String> getGdriveNames() {
        return gdrives.keySet();
    }

    public static Map<String, String> getWorkdocSettings(String name) {
        return workdocs.get(name);
    }

    public static Map<String, String> getGdriveSettings(String name) {
        return gdrives.get(name);
    }

    public static String getContentTypeByFile(File file) {
        String ext = FilenameUtils.getExtension(file.getName());
        String contentType = getContentTypeByExtension(ext);
        if (isNullOrEmpty(contentType)) {
            try {
                contentType = detectContentType(file);
            } catch (IOException e) {
                log.error(e.getMessage(), e);
            }
        }

        if (isNullOrEmpty(contentType)) {
            contentType = "application/octet-stream";  // Default
        }

        return contentType;
    }

    public static String detectContentType(File file) throws IOException {

        InputStream inputStream = null;


        ContentHandler contentHandler = new BodyContentHandler();
        Metadata metadata = new Metadata();
        AutoDetectParser parser = new AutoDetectParser();

        String contentType = null;
        try {
            inputStream = new FileInputStream(file);

            /*
            Alternative form using Unix OS command
            compile group: 'com.j256.simplemagic', name: 'simplemagic', version: '1.12'

            ContentInfo info = new ContentInfoUtil().findMatch(file);
            if (info != null) {
                contentType = info.getMimeType();
            }
            */

            parser.parse(inputStream, contentHandler, metadata);
            contentType = metadata.get(Metadata.CONTENT_TYPE);


        } catch (FileNotFoundException | TikaException | SAXException e) {
            log.error(e.getMessage(), e);
        } finally {
            if (inputStream != null) {
                inputStream.close();
            }
        }

        return contentType;
    }

    public static File getDataStoreDir() {
        return DATA_STORE_DIR;
    }

    private static Map<String, Collection<Map<String, Object>>> loadConfiguration() throws IOException {

        String yamlPath = "settings.yaml";
        if (isNotBlank(getenv("settings_file"))) {
            yamlPath = getenv("settings_file");
        }

        if (isNotBlank(getProperty("SETTINGS_FILE"))) {
            yamlPath = getProperty("SETTINGS_FILE");
        }

        File yamlFile = new File(yamlPath);

        if (!yamlFile.exists()) {
            throw new FileNotFoundException("File not found: " + yamlFile.getAbsolutePath());
        }

        System.out.println("Loaded setup file: " + yamlFile.getAbsolutePath());


        String yamlData = readFileToString(yamlFile, Charset.defaultCharset());

        Yaml yaml = new Yaml();

        Map<String, Collection<Map<String, Object>>> yamlObj = yaml.load(yamlData);

        return yamlObj;
    }

    private final static BiMap<String, String> mimeTypeExtensions;

    private final static Map<String, Map<String, String>> workdocs;

    private final static Map<String, Map<String, String>> gdrives;

    /**
     * Directory to store user credentials for this application.
     */
    private static final java.io.File DATA_STORE_DIR;

    private static Logger log = LoggerFactory.getLogger(DataUtils.class);

    static {

        String delimiter = "|~#|";


        workdocs = new HashMap<>();
        gdrives = new HashMap<>();

        // https://developers.google.com/drive/v3/web/manage-downloads
        mimeTypeExtensions = HashBiMap.create();

        // MS Office
        mimeTypeExtensions.put("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx");
        mimeTypeExtensions.put("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx");
        mimeTypeExtensions.put("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx");

        // Open Office
        mimeTypeExtensions.put("application/vnd.oasis.opendocument.text", "odt");
        mimeTypeExtensions.put("application/x-vnd.oasis.opendocument.spreadsheet", "ods");
        mimeTypeExtensions.put("application/vnd.oasis.opendocument.presentation", "odp");

        // Other
        mimeTypeExtensions.put("application/pdf", "pdf");
        mimeTypeExtensions.put("image/png", "png");
        mimeTypeExtensions.put("image/svg+xml", "svg");

        Map<String, Collection<Map<String, Object>>> yaml = null;
        try {
            yaml = loadConfiguration();
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (yaml == null) {
            System.exit(1);
        }

        Map<String, String> mimeTypes = (Map<String, String>) yaml.get("mimeTypes");
        for (Map.Entry<String, String> mimeType : mimeTypes.entrySet()) {
            mimeTypeExtensions.forcePut(mimeType.getValue(), mimeType.getKey());
        }


        Collection<Map<String, Object>> docs = yaml.get("workdocs");
        for (Map<String, Object> doc : docs) {
            Object name = doc.get("name");
            if (name == null) {
                continue;
            }
            Map<String, String> d = new HashMap<>(doc.size());
            workdocs.put(name.toString(), d);
            for (Map.Entry<String, Object> entry : doc.entrySet()) {
                d.put(entry.getKey(), entry.getValue().toString());
            }
        }


        Collection<Map<String, Object>> drives = yaml.get("gdrives");
        for (Map<String, Object> drive : drives) {
            Object name = drive.get("name");
            if (name == null) {
                continue;
            }
            Map<String, String> d = new HashMap<>(drive.size());
            gdrives.put(name.toString(), d);
            for (Map.Entry<String, Object> entry : drive.entrySet()) {
                String key = entry.getKey();
                String value;
                switch (key) {
                    case "dontDeleteDir":
                        value = String.join(delimiter, (Iterable<? extends CharSequence>) entry.getValue());
                        break;
                    default:
                        value = entry.getValue().toString();
                }
                d.put(key, value);
            }
        }


        String dataStoreDir = new File("").getAbsolutePath();


        Map<String, String> settings = (Map<String, String>) yaml.get("settings");

        if (settings != null && settings.containsKey("dataStoreDir")) {
            dataStoreDir = settings.get("dataStoreDir");
            if (dataStoreDir.startsWith("~/")) {
                dataStoreDir = System.getProperty("user.home") + File.separator + dataStoreDir.substring(2);
            }
        }

        DATA_STORE_DIR = new java.io.File(dataStoreDir);

        DELIMITER = delimiter;
    }

}
