package ie.binary.gdrive2workdocs;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.workdocs.AmazonWorkDocs;
import com.amazonaws.services.workdocs.AmazonWorkDocsClient;
import com.amazonaws.services.workdocs.model.*;
import com.google.api.client.util.Preconditions;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

import static com.google.common.base.Strings.isNullOrEmpty;
import static ie.binary.gdrive2workdocs.DataUtils.getDataStoreDir;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

public class WorkDocsHelper {

    private static Logger log = LoggerFactory.getLogger(WorkDocsHelper.class);
    private final Map<String, String> SETTINGS;
    private final String accessKey;
    private final String secretKey;
    private final String region;
    private AmazonWorkDocs workDocs;
    private String rootFolderId;
    private BiMap<String, String> folderHierarchy;
    private String organisationId;

    public WorkDocsHelper(String name) {

        SETTINGS = DataUtils.getWorkdocSettings(name);
        if (SETTINGS == null || SETTINGS.isEmpty()) {
            throw new java.lang.IllegalArgumentException("Workdoc settings don't exist for: " + name);
        }

        accessKey = SETTINGS.get("accessKey");
        secretKey = SETTINGS.get("secretKey");
        region = SETTINGS.getOrDefault("region", "eu-west-1");

        Preconditions.checkArgument(isNotBlank(accessKey), "Empty accessKey");
        Preconditions.checkArgument(isNotBlank(secretKey), "Empty secretKey");
        Preconditions.checkArgument(isNotBlank(region), "Empty region");
    }

    private AmazonWorkDocs getWorkDocsClient() {
        if (workDocs == null) {
            AWSCredentials longTermCredentials =
                    new BasicAWSCredentials(accessKey, secretKey);
            AWSStaticCredentialsProvider staticCredentialProvider =
                    new AWSStaticCredentialsProvider(longTermCredentials);
            workDocs = AmazonWorkDocsClient.builder().withCredentials(staticCredentialProvider)
                    .withRegion(region).build();
        }

        return workDocs;
    }

    private BiMap<String, String> getFolderHierarchy() {
        if (folderHierarchy == null) {
            log.info("Building folderHierarchy ...");

            Map<String, String> idNameMap = new HashMap<>();
            Map<String, String> parentIdMap = new HashMap<>();

            List<FolderMetadata> folders = getAllFolders();
            for (FolderMetadata folder : folders) {
                idNameMap.put(folder.getId(), folder.getName());
                parentIdMap.put(folder.getId(), folder.getParentFolderId());
            }

            folderHierarchy = HashBiMap.create();
            for (Map.Entry<String, String> entry : idNameMap.entrySet()) {
                String dirId = entry.getKey();
                String dirName = entry.getValue();
                String path = dirName;
                String lastParentId = null;
                do {

                    String parentId = parentIdMap.get(entry.getKey());
                    String parentName = idNameMap.get(parentId);
                    if (parentName == null || parentName.isEmpty() || (lastParentId != null && lastParentId.equals(parentId))) {
                        break;
                    }
                    path = parentName + java.io.File.separator + path;
                    lastParentId = parentId;
                } while (true);

                folderHierarchy.put(dirId, path);
            }

            log.info("folderHierarchy Size:" + folderHierarchy.size());
        }

        return folderHierarchy;
    }

    public String getRootFolderId() {
        if (rootFolderId == null) {
            rootFolderId = SETTINGS.getOrDefault("rootFolderId", "");

            if (isBlank(rootFolderId)) {
                String userQuery = SETTINGS.get("userQuery");
                Preconditions.checkArgument(isNotBlank(userQuery), "Empty userQuery");

                List<User> users = queryUsers(userQuery);
                rootFolderId = users.get(0).getRootFolderId();
            }

            Preconditions.checkArgument(isNotBlank(rootFolderId), "Empty rootFolderId");
        }

        return rootFolderId;
    }

    public String getOrganizationId() {

        if (organisationId == null) {
            organisationId = SETTINGS.get("organisationId");
            Preconditions.checkArgument(isNotBlank(organisationId), "Empty organisationId");
        }

        return organisationId;
    }

    public List<User> queryUsers(String query) {
        List<User> wdUsers = new ArrayList<>();
        DescribeUsersRequest request = new DescribeUsersRequest();
        request.setOrganizationId(getOrganizationId());
        request.setQuery(query);
        String marker = null;
        do {
            request.setMarker(marker);
            DescribeUsersResult result = getWorkDocsClient().describeUsers(request);
            wdUsers.addAll(result.getUsers());
            marker = result.getMarker();
        } while (marker != null);
        log.info("List of users matching the query string: " + query);

        for (User wdUser : wdUsers) {
            log.info("Firstname: {} | Lastname: {} | Email: {} | Username: {} | root-folder-id: {}",
                    wdUser.getGivenName(), wdUser.getSurname(), wdUser.getEmailAddress(), wdUser.getUsername(),
                    wdUser.getRootFolderId());
        }

        return wdUsers;
    }

    public void subscribe() {
        CreateNotificationSubscriptionRequest request = new
                CreateNotificationSubscriptionRequest();
        request.setOrganizationId(getOrganizationId());
        request.setProtocol(SubscriptionProtocolType.HTTPS);
        request.setEndpoint("https://my-webhook-service.com/webhook");
        request.setSubscriptionType(SubscriptionType.ALL);
        CreateNotificationSubscriptionResult result =
                getWorkDocsClient().createNotificationSubscription(request);
        log.info("WorkDocs notifications subscription-id: " +
                result.getSubscription().getSubscriptionId());
    }

    public List<FolderMetadata> getAllFolders() {
        List<FolderMetadata> folders = new LinkedList<>();
        _fetchFolders(folders, getFolders(getRootFolderId()));
        return folders;
    }

    private void _fetchFolders(List<FolderMetadata> folders, List<FolderMetadata> seed) {

        if (seed == null || seed.isEmpty()) {
            return;
        }

        for (FolderMetadata folder : seed) {
            folders.add(folder);
            _fetchFolders(folders, getFolders(folder.getId()));
        }
    }

    public List<FolderMetadata> getFolders(String folderId) {
        DescribeFolderContentsRequest folderContentsRequest = new DescribeFolderContentsRequest()
                .withFolderId(folderId);
        DescribeFolderContentsResult folderContentsResult = getWorkDocsClient().describeFolderContents(folderContentsRequest);

        return folderContentsResult.getFolders();
    }

    public List<File> uploadDirectory(File directory) throws IOException {
        File[] files = directory.listFiles();
        List<File> uploaded = new ArrayList<>(files.length);
        for (File file : files) {
            if (uploadFile(file)) {
                uploaded.add(file);
            }
        }

        return uploaded;
    }

    public boolean uploadFile(File file) throws IOException {
        String folderPath = file.getParentFile().getAbsolutePath();
        String storePath = getDataStoreDir().getAbsolutePath();

        folderPath = folderPath.replace(storePath + File.separator, "");

        String fileName = file.getName();
        String contentType = DataUtils.getContentTypeByFile(file);
        Date lastModified = new Date(file.lastModified());

        FileInputStream inputStream = null;
        try {
            inputStream = new FileInputStream(file);
            return uploadFile(folderPath, fileName, contentType, lastModified, inputStream);
        } finally {
            if (inputStream != null) {
                inputStream.close();
            }
        }
    }

    public String createFolder(String folderPath) {

        if (isNullOrEmpty(folderPath)) {
            return getRootFolderId();
        }

        String folderId = getFolderHierarchy().inverse().get(folderPath);

        if (!isNullOrEmpty(folderId)) {
            return folderId; // It already exists
        }


        File dir = new File(folderPath);
        File parent = dir.getParentFile();
        String parentFolderId;
        if (parent == null) {
            parentFolderId = getRootFolderId();
        } else {
            String parentFolderPath = parent.getPath();
            parentFolderId = createFolder(parentFolderPath);
        }

        CreateFolderRequest createFolderRequest = new CreateFolderRequest().withName(dir.getName()).withParentFolderId(parentFolderId);
        CreateFolderResult createFolderResult = getWorkDocsClient().createFolder(createFolderRequest);
        folderId = createFolderResult.getMetadata().getId();
        getFolderHierarchy().put(folderId, folderPath);
        return folderId;
    }

    public boolean uploadFile(String folderPath, String fileName, String contentType, Date lastModified, InputStream inputStream) throws IOException {

        String[] illegalCharacters = {"*", "/", ":", "<", ">", "?", "\\", "|"};
        for (String illegalCharacter : illegalCharacters) {
            fileName = StringUtils.replace(fileName, illegalCharacter, "_");
        }

        log.info("Uploading to WorkDocs: " + fileName);

        String awzEncryption = "AES256";
        //String awzEncryption = "aws:kms";

        String parentFolderId = getFolderHierarchy().inverse().get(folderPath);
        if (isNullOrEmpty(parentFolderId)) {
            parentFolderId = createFolder(folderPath);
        }

        // Get the signed URL for the upload
        InitiateDocumentVersionUploadRequest request = new InitiateDocumentVersionUploadRequest()
                .withParentFolderId(parentFolderId)
                .withName(fileName)
                .withContentType(contentType)
                .withContentCreatedTimestamp(lastModified)
                .withContentModifiedTimestamp(lastModified);

        InitiateDocumentVersionUploadResult result = getWorkDocsClient().initiateDocumentVersionUpload(request);
        UploadMetadata uploadMetadata = result.getUploadMetadata();

        String documentId = result.getMetadata().getId();
        String documentVersionId = result.getMetadata().getLatestVersionMetadata().getId();
        String uploadUrl = uploadMetadata.getUploadUrl();

        log.info("documentId: " + documentId);
        log.info("documentVersionId: " + documentVersionId);
        log.info("uploadUrl: " + uploadUrl);

        // Upload the document using the signed URL
        URL url = new URL(uploadUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setDoOutput(true);
        connection.setRequestMethod("PUT");

        connection.setRequestProperty("Content-Type", contentType);
        connection.setRequestProperty("x-amz-server-side-encryption", awzEncryption);
        if ("kms".equals(awzEncryption)) {
            //  connection.setRequestProperty("x-amz-server-side-encryption-aws-kms-key-id", "no-key-defined");
        }

        OutputStream outputStream = connection.getOutputStream();
        com.amazonaws.util.IOUtils.copy(inputStream, outputStream);
        connection.getResponseCode();


        // Complete the upload process by changing the document status to ACTIVE

        UpdateDocumentVersionRequest updateDocVerRequest = new UpdateDocumentVersionRequest();
        updateDocVerRequest.setDocumentId(documentId);
        updateDocVerRequest.setVersionId(documentVersionId);
        updateDocVerRequest.setVersionStatus(DocumentVersionStatus.ACTIVE);
        getWorkDocsClient().updateDocumentVersion(updateDocVerRequest);

        return true;
    }
}