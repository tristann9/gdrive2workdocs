package ie.binary.gdrive2workdocs;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.client.util.IOUtils;
import com.google.api.client.util.Preconditions;
import com.google.api.client.util.store.DataStoreFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.client.util.store.MemoryDataStoreFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;

import static com.google.common.base.Strings.isNullOrEmpty;
import static humanize.Humanize.binaryPrefix;
import static ie.binary.gdrive2workdocs.DataUtils.DELIMITER;
import static ie.binary.gdrive2workdocs.DataUtils.getDataStoreDir;
import static org.apache.commons.lang3.BooleanUtils.toBoolean;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.apache.commons.lang3.StringUtils.split;

public class GDriveHelper {

    /**
     * Global instance of the scopes required by this quickstart.
     * <p>
     * If modifying these scopes, delete your previously saved credentials
     * at ~/.credentials/drive-java-quickstart
     */
    private static final List<String> SCOPES =
            Arrays.asList(DriveScopes.DRIVE_FILE,
                    DriveScopes.DRIVE_APPDATA,
                    DriveScopes.DRIVE);
    /**
     * Global instance of the {@link FileDataStoreFactory}.
     */
    private static DataStoreFactory DATA_STORE_FACTORY;
    /**
     * Global instance of the HTTP transport.
     */
    private static HttpTransport HTTP_TRANSPORT;
    private static Logger log = LoggerFactory.getLogger(GDriveHelper.class);

    static {
        try {
            HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
            DATA_STORE_FACTORY = new FileDataStoreFactory(getDataStoreDir());
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Global instance of the JSON factory.
     */
    private final JsonFactory JSON_FACTORY =
            JacksonFactory.getDefaultInstance();
    private final int fileFetchSize;
    private final int folderHierarchyFetchSize;
    private final String localServerReceiverHost;
    private final String clientId;
    private final String clientSecret;
    private final String localServerReceiverCallbackPath;
    private final String applicationName;
    private final int localServerReceiverPort;
    private final Map<String, String> SETTINGS;
    private BiMap<String, String> folderHierarchy;
    private Drive service;


    public GDriveHelper(String name) {
        SETTINGS = DataUtils.getGdriveSettings(name);
        if (SETTINGS == null || SETTINGS.isEmpty()) {
            throw new java.lang.IllegalArgumentException("GDrive settings don't exist for: " + name);
        }


        fileFetchSize = NumberUtils.toInt(SETTINGS.getOrDefault("fileFetchSize", "1000"));
        folderHierarchyFetchSize = NumberUtils.toInt(SETTINGS.getOrDefault("folderHierarchyFetchSize", "1000"));
        localServerReceiverPort = NumberUtils.toInt(SETTINGS.getOrDefault("localServerReceiverPort", "5432"));
        localServerReceiverHost = SETTINGS.getOrDefault("localServerReceiverHost", "localhost");
        localServerReceiverCallbackPath = SETTINGS.getOrDefault("localServerReceiverCallbackPath", "/Callback");
        applicationName = SETTINGS.getOrDefault("applicationName", "gdrive2workdocs");
        clientId = SETTINGS.get("clientId");
        clientSecret = SETTINGS.get("clientSecret");

        Preconditions.checkArgument(isNotBlank(localServerReceiverHost), "Empty localServerReceiverHost");
        Preconditions.checkArgument(isNotBlank(localServerReceiverCallbackPath), "Empty localServerReceiverCallbackPath");
        Preconditions.checkArgument(isNotBlank(applicationName), "Empty applicationName");
        Preconditions.checkArgument(isNotBlank(clientId), "Empty clientId");
        Preconditions.checkArgument(isNotBlank(clientSecret), "Empty clientSecret");
    }

    /**
     * Creates an authorized Credential object.
     *
     * @return an authorized Credential object.
     * @throws IOException
     */
    public Credential authorize() throws IOException {
        /*
        // Load client secrets.
        InputStream in =
                GDriveHelper.class.getResourceAsStream("client_secret.json");
        GoogleClientSecrets clientSecrets =
                GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));
        */
        GoogleClientSecrets clientSecrets = new GoogleClientSecrets();
        //clientSecrets.set("client_id", clientId);
        //clientSecrets.set("client_secret", clientSecret);

        GoogleClientSecrets.Details details = new GoogleClientSecrets.Details();
        details.setClientId(clientId);
        details.setClientSecret(clientSecret);
        clientSecrets.setInstalled(details);


        // Build flow and trigger user authorization request.
        GoogleAuthorizationCodeFlow flow =
                new GoogleAuthorizationCodeFlow.Builder(
                        HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, SCOPES)
                        .setDataStoreFactory(DATA_STORE_FACTORY)
                        .setAccessType("offline")
                        .build();


        LocalServerReceiver localServerReceiver = new LocalServerReceiver.Builder()
                .setHost(localServerReceiverHost)
                .setPort(localServerReceiverPort)
                .setCallbackPath(localServerReceiverCallbackPath).build();

        Credential credential = new AuthorizationCodeInstalledApp(
                flow, localServerReceiver).authorize("user");
        log.info("Credentials saved to " + getDataStoreDir().getAbsolutePath());
        return credential;
    }

    /**
     * Build and return an authorized Drive client service.
     *
     * @return an authorized Drive client service
     * @throws IOException
     */
    public Drive getDriveService() throws IOException {
        if (service == null) {
            Credential credential = authorize();
            service = new Drive.Builder(
                    HTTP_TRANSPORT, JSON_FACTORY, credential)
                    .setApplicationName(applicationName)
                    .build();
        }
        return service;
    }

    public void start() throws IOException {
        WorkDocsHelper awsDestination = null;


        boolean downloadOnly = toBoolean(SETTINGS.getOrDefault("downloadOnly", "false"));
        boolean cleanup = toBoolean(SETTINGS.getOrDefault("cleanup", "false"));
        String destinationName = SETTINGS.get("destination");


        if (!downloadOnly && isNotBlank(destinationName)) {
            awsDestination = new WorkDocsHelper(destinationName);
        }


        buildFolderHierarchy();

        FileList result = getDriveService().files().list()
                .setPageSize(fileFetchSize)
                //.setQ("'root' in parents and trashed = false")
                .setFields("nextPageToken, files(id, name, parents, kind, mimeType, modifiedTime, md5Checksum, size)")
                .setOrderBy("quotaBytesUsed")
                .execute();
        List<File> files = result.getFiles();
        if (files == null || files.size() == 0) {
            log.info("No files found.");
        } else {
            log.info(files.size() + " files");
            for (File file : files) {

                try {
                    List<java.io.File> downloadFiles = downloadFile(file);

                    if (!downloadFiles.isEmpty() && awsDestination != null) {
                        List<Boolean> uploadPassed = new ArrayList<>(downloadFiles.size());
                        for (java.io.File downloadFile : downloadFiles) {
                            if (awsDestination.uploadFile(downloadFile) && cleanup) {
                                uploadPassed.add(true);
                                downloadFile.delete();
                            }
                        }

                        if (cleanup && uploadPassed.size() == downloadFiles.size()) {
                            deleteFile(file);
                        }
                    }
                } catch (IOException e) {
                    log.error(e.getMessage(), e);
                }
            }
        }
    }

    private void deleteFile(File file) throws IOException {
        boolean delete = true;
        String parentPath = getParentPath(file);
        if (parentPath == null) {
            parentPath = "";
        }

        if (!parentPath.startsWith("/")) {
            parentPath = "/" + parentPath;
        }


        String dontDeleteDir = SETTINGS.getOrDefault("dontDeleteDir", "");
        if (isNotBlank(dontDeleteDir)) {
            List<String> dirPaths = Arrays.asList(split(dontDeleteDir, DELIMITER));
            for (String dirPath : dirPaths) {
                if (parentPath.startsWith(dirPath)) {
                    delete = false;
                    break;
                }
            }
        }

        if (delete) {
            log.info("Deleting GDrive file: " + file.getName());
            //getDriveService().files().delete(file.getId()).execute();
        } else {
            log.info("Skipping deletion of GDrive file [{}] because it falls under path: {}", file.getName(), parentPath);
        }
    }

    private void buildFolderHierarchy() throws IOException {

        log.info("Building folderHierarchy ...");

        Map<String, String> idNameMap = new HashMap<>();
        Map<String, String> parentIdMap = new HashMap<>();
        Map<String, File> folders = new HashMap<>();

        FileList result = getDriveService().files().list()
                .setPageSize(folderHierarchyFetchSize)
                .setQ("mimeType = 'application/vnd.google-apps.folder'")
                .setFields("nextPageToken, files(id, name, parents)")
                .execute();
        List<File> files = result.getFiles();
        if (files == null || files.size() == 0) {
            log.info("No folders found.");
        } else {
            for (File file : files) {
                folders.put(file.getId(), file);
                idNameMap.put(file.getId(), file.getName());
                List<String> parents = file.getParents();
                if (parents != null) {
                    for (String parentId : file.getParents()) {
                        parentIdMap.put(file.getId(), parentId);
                    }
                }
            }


        }


        folderHierarchy = HashBiMap.create();
        for (File file : files) {
            String path = determineFullPath(folders, file);
            folderHierarchy.forcePut(file.getId(), path);
        }

        log.info("folderHierarchy Size:" + folderHierarchy.size());
    }

    private String determineFullPath(Map<String, File> folders, File folder) {

        List<String> parents = folder.getParents();
        File parent = null;
        if (parents != null && !parents.isEmpty()) {
            String parentId = parents.get(0);
            parent = folders.get(parentId);
        }


        if (parent == null) {
            String rootDirName = SETTINGS.getOrDefault("rootDirName", "");
            if (!rootDirName.startsWith("/")) {
                rootDirName = "/" + rootDirName;
            }
            return rootDirName;
        }

        String parentPath = determineFullPath(folders, parent);
        if (!parentPath.startsWith("/")) {
            parentPath = "/" + parentPath;
        }

        parentPath = parentPath + parent.getName() + "/" + folder.getName();

        return parentPath;
    }

    private String getParentPath(File file) {
        List<String> parents = file.getParents();
        String rootDirName = SETTINGS.getOrDefault("rootDirName", "");
        String parentPath = rootDirName;
        if (parents != null && !parents.isEmpty()) {
            String parentId = parents.get(0);
            parentPath = folderHierarchy.getOrDefault(parentId, rootDirName);
        }

        if (!parentPath.startsWith("/")) {
            parentPath = "/" + parentPath;
        }

        return parentPath;
    }

    private List<java.io.File> downloadFile(File driveFile) throws IOException {
        String fileName = driveFile.getName();
        fileName = fileName.replace("/", "-");

        String parentPath = getParentPath(driveFile);

        if (parentPath.startsWith("/")) {
            parentPath = parentPath.substring(1);
        }

        String targetDirName = SETTINGS.getOrDefault("targetDirName", "GDrive");

        java.io.File parentFile = new java.io.File(new java.io.File(getDataStoreDir(), targetDirName), parentPath);
        parentFile.mkdirs();

        DateTime modifiedTime = driveFile.getModifiedTime();


        String mimeType = driveFile.getMimeType();

        List<String> exportMimeTypes = new ArrayList<>();
        switch (mimeType) {
            case "application/vnd.google-apps.folder":
                return new ArrayList<>();
            case "application/vnd.google-apps.document":
                exportMimeTypes.add("application/vnd.openxmlformats-officedocument.wordprocessingml.document"); // MS Word document
                // exportMimeTypes.add("application/vnd.oasis.opendocument.text"); // Open Office doc
                exportMimeTypes.add("application/pdf"); // PDF
                break;
            case "application/vnd.google-apps.spreadsheet":
                exportMimeTypes.add("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"); // MS Excel
                //exportMimeTypes.add("application/x-vnd.oasis.opendocument.spreadsheet"); // Open Office sheet
                break;
            case "application/vnd.google-apps.drawing":
                exportMimeTypes.add("image/png"); // PNG
                exportMimeTypes.add("image/svg+xml"); // SVG
                break;
            case "application/vnd.google-apps.presentation":
                exportMimeTypes.add("application/vnd.openxmlformats-officedocument.presentationml.presentation"); // MS PowerPoint
                //exportMimeTypes.add("application/vnd.oasis.opendocument.presentation"); // Open Office presentation
                exportMimeTypes.add("application/pdf"); // PDF
                break;
        }


        Drive.Files driveFiles = getDriveService().files();

        List<java.io.File> downloadedFiles = new ArrayList<>();


        log.info("Downloading [{}/{}] ({})", parentPath, driveFile.getName(), driveFile.getMimeType());

        if (exportMimeTypes.isEmpty()) {
            java.io.File outFile = new java.io.File(parentFile, fileName);
            OutputStream out = null;
            try {
                out = new FileOutputStream(outFile);
                try {
                    driveFiles.get(driveFile.getId()).executeMediaAndDownloadTo(out);
                } catch (HttpResponseException e1) {
                    // 416 - Requested range not satisfiable
                    if (e1.getStatusCode() == 416) {
                        log.warn("Skipped [{}]: {}", driveFile.getName(), e1.getMessage());
                    } else {
                        throw e1;
                    }
                }

                if (modifiedTime != null && modifiedTime.getValue() > 0) {
                    outFile.setLastModified(modifiedTime.getValue());
                }

                downloadedFiles.add(outFile);
                log.info("\tDownloaded [{}] ({})", outFile, binaryPrefix(outFile.length()));
            } finally {
                if (out != null) {
                    out.close();
                }
            }
        } else {
            for (String exportMimeType : exportMimeTypes) {
                String ext = DataUtils.getExtensionSuffix(exportMimeType);

                if (!isNullOrEmpty(ext)) {
                    String extension = "." + ext;
                    String outFileName = fileName.endsWith(extension) ? fileName : fileName + extension;
                    java.io.File outFile = new java.io.File(parentFile, outFileName);
                    OutputStream out = null;
                    try {
                        out = new FileOutputStream(outFile);

                        try {
                            driveFiles.export(driveFile.getId(), exportMimeType).executeMediaAndDownloadTo(out);
                        } catch (HttpResponseException e1) {
                            // 416 - Requested range not satisfiable
                            if (e1.getStatusCode() == 416) {
                                log.warn("Skipped [{}]: {}", driveFile.getName(), e1.getMessage());
                                outFile.delete();
                            } else {
                                throw e1;
                            }
                        }

                        if (modifiedTime != null && modifiedTime.getValue() > 0) {
                            outFile.setLastModified(modifiedTime.getValue());
                        }

                        downloadedFiles.add(outFile);

                        log.info("\tExported [{}] ({})", outFile, binaryPrefix(outFile.length()));
                    } finally {
                        if (out != null) {
                            out.close();
                        }
                    }

                }
            }
        }

        return downloadedFiles;

    }
}