// src/orgs/server/ChatServer2.java
package orgs.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import orgs.dao.*;
import orgs.model.*; // Make sure Media is imported
import orgs.protocol.*;
import orgs.protocol.Request;
import orgs.protocol.Response;
import orgs.utils.FileStorageManager;
import orgs.utils.LocalDateTimeAdapter;

import java.io.*;
import java.lang.reflect.Type;
import java.net.*;
import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static orgs.protocol.Command.*;


public class ChatServer2 {
    private static final int PORT = 6373;
    private static final int FILE_TRANSFER_PORT = 6374;
    private ExecutorService commandPool = Executors.newFixedThreadPool(10);
    private ExecutorService fileTransferPool = Executors.newCachedThreadPool();

    private UserDao userDao = new UserDao();
    private MessageDao messageDao = new MessageDao();
    private ChatDao chatDao = new ChatDao();
    private ChatParticipantDao chatParticipantDao = new ChatParticipantDao();
    private ContactDao contactDao = new ContactDao();
    private NotificationDao notificationDao = new NotificationDao();
    private  MediaDao mediaDao = new MediaDao() ;

    private Gson gson = new GsonBuilder()
            .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeAdapter())
            .serializeNulls()
            .create();

    //private static final Map<Integer, ClientHandler2> loggedInUsers = new ConcurrentHashMap<>();
    // In ChatServer class
    private ConcurrentHashMap<Integer, ClientHandler2> loggedInUsers = new ConcurrentHashMap<>(); // Already exists
    // New: Map current active video calls (CallerId -> CalleeId) for direct P2P info exchange
    private ConcurrentHashMap<Integer, Integer> activeVideoCalls = new ConcurrentHashMap<>();
    // New: Map UserId to their discovered public IP and UDP port (Crucial for P2P media)
    private ConcurrentHashMap<Integer, String> userPublicIPs = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Integer, Integer> userUdpPorts = new ConcurrentHashMap<>();


    // Map to temporarily store pending file transfer details
    // Key: unique transferId (UUID string), Value: FileTransferMetadata
    private final ConcurrentHashMap<String, FileTransferMetadata> pendingFileTransfers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, FileTransferMetadata> pendingFileDownloads = new ConcurrentHashMap<>();


    // Optional: UDP Relay Server (if direct P2P fails/isn't feasible)
    private static final int VIDEO_RELAY_PORT = 6375;
    private DatagramSocket videoRelaySocket;
    private Thread videoRelayThread;


    // Helper class to hold file transfer metadata
    private static class FileTransferMetadata {
        int senderId;
        int chatId;
        String fileName;
        long fileSize;
        String mediaType;
        String caption; // Now corresponds to Message.content for media messages
        String transferId; // Add transferId to metadata for easier lookup/logging
        int mediaID ;

        public FileTransferMetadata(int senderId, int chatId, String fileName, long fileSize, String mediaType, String caption, String transferId ,int mediaID) {
            this.senderId = senderId;
            this.chatId = chatId;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.mediaType = mediaType;
            this.caption = caption;
            this.transferId = transferId;
            this.mediaID = mediaID ;
        }
    }

    public ChatServer2() {
        FileStorageManager.createUploadDirectory();
        try {
            videoRelaySocket = new DatagramSocket(VIDEO_RELAY_PORT);
            System.out.println("Video Relay Server initialized on UDP port " + VIDEO_RELAY_PORT);
        } catch (IOException e) {
            System.err.println("Failed to initialize Video Relay Server: " + e.getMessage());
            e.printStackTrace();
        }
    }
    private void runVideoRelayServer() {
        byte[] buffer = new byte[65507]; // Max UDP packet size
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (!videoRelaySocket.isClosed()) {
            try {
                videoRelaySocket.receive(packet);
                // The received packet must contain metadata to tell the server who the recipient is.
                // This is a VERY simplified example. Real TURN servers are complex.
                // For instance, the first few bytes could be the recipient's userId.
                ByteBuffer bBuffer = ByteBuffer.wrap(packet.getData(), 0, packet.getLength());
                if (bBuffer.remaining() >= 4) { // Ensure enough bytes for recipient ID
                    int recipientId = bBuffer.getInt();
                    ClientHandler2 recipientHandler = loggedInUsers.get(recipientId);

                    if (recipientHandler != null && userPublicIPs.containsKey(recipientId) && userUdpPorts.containsKey(recipientId)) {
                        InetAddress recipientAddress = InetAddress.getByName(userPublicIPs.get(recipientId));
                        int recipientPort = userUdpPorts.get(recipientId);

                        // Create a new packet to forward, excluding the recipientId header
                        DatagramPacket forwardPacket = new DatagramPacket(
                                packet.getData(), 4, packet.getLength() - 4,
                                recipientAddress, recipientPort
                        );
                        videoRelaySocket.send(forwardPacket);
                    } else {
                        System.err.println("Received UDP packet for unknown/offline recipient: " + recipientId);
                    }
                }
            } catch (IOException e) {
                if (!videoRelaySocket.isClosed()) {
                    System.err.println("Error in UDP video relay: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
        System.out.println("Video Relay Server stopped.");
    }

    public void stop() {
        // ... existing stop logic ...
        if (videoRelaySocket != null && !videoRelaySocket.isClosed()) {
            videoRelaySocket.close();
        }
        if (videoRelayThread != null) {
            videoRelayThread.interrupt();
        }
    }

    public void start() {
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(PORT)) {
                System.out.println("Chat Server started on command port " + PORT);
                while (true) {
                    Socket clientSocket = serverSocket.accept();
                    System.out.println("New client connected (command): " + clientSocket.getInetAddress().getHostAddress());
                    ClientHandler2 ClientHandler2 = new ClientHandler2(clientSocket);
                    commandPool.execute(ClientHandler2);
                }
            } catch (IOException e) {
                System.err.println("Command Server error: " + e.getMessage());
                e.printStackTrace();
            } finally {
                commandPool.shutdown();
            }
        }, "CommandServerListener").start();



        new Thread(() -> {
            try (ServerSocket fileTransferServerSocket = new ServerSocket(FILE_TRANSFER_PORT)) {
                System.out.println("File Transfer Server started on port " + FILE_TRANSFER_PORT);
                while (true) {
                    Socket fileClientSocket = fileTransferServerSocket.accept();
                    System.out.println("New client connected (file transfer): " + fileClientSocket.getInetAddress().getHostAddress());

                    try {
                        BufferedReader fileIn = new BufferedReader(new InputStreamReader(fileClientSocket.getInputStream()));
                        String transferId = fileIn.readLine();
                        if (transferId == null || transferId.isEmpty()) {
                            System.err.println("File transfer: Received empty or null transferId from " + fileClientSocket.getInetAddress());
                            fileClientSocket.close();
                            continue;
                        }

                        // Check if it's an upload
                        FileTransferMetadata uploadMetadata = pendingFileTransfers.remove(transferId);
                        if (uploadMetadata != null) {
                            System.out.println("File transfer: Initiating upload for file " + uploadMetadata.fileName + " (transferId: " + transferId + ")");
                            fileTransferPool.execute(new FileTransferHandler(
                                    fileClientSocket, uploadMetadata.senderId, uploadMetadata.chatId,
                                    uploadMetadata.fileName, uploadMetadata.fileSize,
                                    uploadMetadata.mediaType, uploadMetadata.caption,
                                    uploadMetadata.transferId, messageDao, ChatServer2.this ,uploadMetadata.mediaID ));
                            continue;
                        }

                        // Check if it's a download pendingFileTransfers
                        FileTransferMetadata downloadMetadata = pendingFileDownloads.remove(transferId);
                        if (downloadMetadata != null) {
                            System.out.println("File transfer: Initiating download for file " + downloadMetadata.fileName + " (transferId: " + transferId + ")");
                            fileTransferPool.execute(new FileDownloadHandler(
                                    fileClientSocket, downloadMetadata.fileName, downloadMetadata.transferId)); // transferId is now the mediaId
                            continue;
                        }

                        // No metadata found
                        System.err.println("File transfer: No pending metadata found for transferId: " + transferId + " from " + fileClientSocket.getInetAddress());
                        fileClientSocket.close();

                    } catch (IOException e) {
                        System.err.println("Error setting up file transfer handler: " + e.getMessage());
                        try {
                            fileClientSocket.close();
                        } catch (IOException ex) {
                            System.err.println("Error closing file client socket: " + ex.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                System.err.println("File Transfer Server error: " + e.getMessage());
                e.printStackTrace();
            } finally {
                fileTransferPool.shutdown();
            }
        }, "FileTransferServerListener").start();

        if (videoRelaySocket != null) {
            videoRelayThread = new Thread(this::runVideoRelayServer, "VideoRelayServer");
            videoRelayThread.start();
        }


    }






    // New FileTransferHandler to store the message after file is received
    private class FileTransferHandler implements Runnable {
        private Socket fileSocket;
        private int senderId;
        private int chatId;
        private String fileName;
        private long fileSize;
        private String mediaType;
        private String caption;
        private String transferId; // The UUID used for this transfer
        private MessageDao messageDao;
        private ChatServer2 server;
        private int mediaId ;// Reference to the outer class


        public FileTransferHandler(Socket fileSocket, int senderId, int chatId, String fileName, long fileSize, String mediaType, String caption, String transferId, MessageDao messageDao, ChatServer2 server, int mediaId) {
            this.fileSocket = fileSocket;
            this.senderId = senderId;
            this.chatId = chatId;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.mediaType = mediaType;
            this.caption = caption;
            this.transferId = transferId;
            this.messageDao = messageDao;
            this.server = server;
            this.mediaId =mediaId ;
        }

        @Override
        public void run() {
            String filePathOnServer = FileStorageManager.getUploadDirectory() + File.separator + transferId + "_" + fileName;
            //String path = FileStorageManager.getUploadDirectory() ;
            //String filePathOnServer = path + File.separator +fileName;
            try (InputStream is = fileSocket.getInputStream();
                 FileOutputStream fos = new FileOutputStream(filePathOnServer);
                 PrintWriter fileOut = new PrintWriter(fileSocket.getOutputStream(), true)) {

                byte[] buffer = new byte[4096];
                int bytesRead;
                long totalBytesReceived = 0;

                System.out.println("Receiving file: " + fileName + " (" + fileSize + " bytes) to " + filePathOnServer);

                while (totalBytesReceived < fileSize && (bytesRead = is.read(buffer, 0, (int) Math.min(buffer.length, fileSize - totalBytesReceived))) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    totalBytesReceived += bytesRead;
                    // Optional: Print progress
                    // System.out.print("\rReceived: " + totalBytesReceived + " / " + fileSize + " bytes");
                }
                fos.flush();

                if (totalBytesReceived == fileSize) {
                    System.out.println("\nFile '" + fileName + "' received successfully. Size: " + totalBytesReceived + " bytes.");
                    fileOut.println("File transfer complete: " + fileName);

                    // Save the message to the database AFTER the file is fully received
                    Message message = new Message();
                    message.setChatId(chatId);
                    message.setSenderId(senderId);
                    message.setContent(caption); // Caption is now the content field
                    message.setSentAt(LocalDateTime.now());
                    message.setViewCount(0);
                    message.setMediaId(mediaId);
                    mediaType= mediaType == null? "text" : mediaType ;
                    message.setMessageType(mediaType);

                    // Create and set the Media object
                    Media media = new Media();
                    media.setMediaType(mediaType);
                    media.setFileName(fileName);
                    media.setFileSize(fileSize);
                    media.setTransferId(transferId); // Use transferId as the unique media ID/path on server

                    message.setMedia(media);

                    int messageId = messageDao.createMessage(message);

                    if (messageId != -1) {
                        message.setId(messageId);
                        // Notify all participants about the new media message
                        // (chatId, new Response(true, "New message received", gson.toJson(message)));
                    } else {
                        System.err.println("Failed to save media message metadata to DB for file: " + fileName);
                    }

                } else {
                    System.err.println("\nFile transfer incomplete for '" + fileName + "'. Expected: " + fileSize + ", Received: " + totalBytesReceived);
                    fileOut.println("File transfer failed: Incomplete.");
                }

            } catch (IOException e) {
                System.err.println("Error during file transfer for " + fileName + ": " + e.getMessage());
                e.printStackTrace();
            } finally {
                try {
                    if (fileSocket != null && !fileSocket.isClosed()) {
                        fileSocket.close();
                    }
                } catch (IOException e) {
                    System.err.println("Error closing file socket: " + e.getMessage());
                }
            }
        }

    }


    private class ClientHandler2 implements Runnable {
        private Socket clientSocket;
        private PrintWriter out;
        private BufferedReader in;
        private int currentUserId = -1;
        private String clientPublicIp;
        private int clientUdpPort;

        public ClientHandler2(Socket socket) {
            this.clientSocket = socket;
        }

        @Override
        public void run() {
            try {
                out = new PrintWriter(clientSocket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

                String clientRequestJson;
                while ((clientRequestJson = in.readLine()) != null) {
                    if (clientRequestJson.trim().isEmpty()) {
                        continue;
                    }
                    Request request = gson.fromJson(clientRequestJson, Request.class);
                    processRequest(request);
                }
            } catch (IOException e) {
                if (currentUserId != -1) {
                    System.out.println("Client " + currentUserId + " disconnected.");
                    loggedInUsers.remove(currentUserId);
                    userDao.updateUserOnlineStatus(currentUserId, false);
                } else {
                    System.out.println("Client disconnected unexpectedly: " + clientSocket.getInetAddress().getHostAddress() + " - " + e.getMessage());
                }
            } finally {
                try {
                    if (currentUserId != -1) {
                        loggedInUsers.remove(currentUserId);
                        userDao.updateUserOnlineStatus(currentUserId, false);
                    }
                    if (in != null) in.close();
                    if (out != null) out.close();
                    if (clientSocket != null && !clientSocket.isClosed()) clientSocket.close();
                } catch (IOException e) {
                    System.err.println("Error closing client handler resources: " + e.getMessage());
                }
            }
        }

        private void processRequest(Request request) {
            Response response;

            if (currentUserId == -1 && !request.getCommand().equals(LOGIN) && !request.getCommand().equals(Command.REGISTER)) {
                response = new Response(false, "Authentication required. Please log in.", null);
                out.println(response.toJson());
                return;
            }

            try {
                switch (request.getCommand()) {
                    case LOGIN:
                        response = handleLogin(request.getPayload());
                        break;
                    case LOGOUT:
                        response = handleLogout();
                        break;
                    case SEND_MESSAGE: // Unified command
                        response = handleSendMessage(request.getPayload());
                        break;
                    case GET_CHAT_MESSAGES:
                        response = handleGetChatMessages(request.getPayload());
                        break;
                    case CREATE_CHAT:
                        response = handleCreateChat(request.getPayload());
                        break;

                    case REGISTER:
                        response = handleRegister(request.getPayload());
                        break;
                    case GET_USER_PROFILE:
                        response = handleGetUserProfile(request.getPayload());
                        break;
                    case UPDATE_USER_PROFILE:
                        response = handleUpdateUserProfile(request.getPayload());
                        break;
                    case DELETE_USER:
                        response = handleDeleteUser(request.getPayload());
                        break;
                    case GET_ALL_USERS:
                        response = handleGetAllUsers();
                        break;

                    case GET_USER_CHATS:
                        response = handleGetUserChats();
                        break;
                    case GET_CHAT_DETAILS:
                        response = handleGetChatDetails(request.getPayload());
                        break;
                    case UPDATE_CHAT:
                        response = handleUpdateChat(request.getPayload());
                        break;
                    case DELETE_CHAT:
                        response = handleDeleteChat(request.getPayload());
                        break;

                    case UPDATE_MESSAGE:
                        response = handleUpdateMessage(request.getPayload());
                        break;
                    case DELETE_MESSAGE:
                        response = handleDeleteMessage(request.getPayload());
                        break;
                    case MARK_MESSAGE_AS_READ:
                        response = handleMarkMessageAsRead(request.getPayload());
                        break;

                    case ADD_CHAT_PARTICIPANT:
                        response = handleAddChatParticipant(request.getPayload());
                        break;
                    case GET_CHAT_PARTICIPANTS:
                        response = handleGetChatParticipants(request.getPayload());
                        break;
                    case UPDATE_CHAT_PARTICIPANT_ROLE:
                        response = handleUpdateChatParticipantRole(request.getPayload());
                        break;
                    case REMOVE_CHAT_PARTICIPANT:
                        response = handleRemoveChatParticipant(request.getPayload());
                        break;

                    case ADD_CONTACT:
                        response = handleAddContact(request.getPayload());
                        break;
                    case GET_CONTACTS:
                        response = handleGetContacts();
                        break;
                    case REMOVE_CONTACT:
                        response = handleRemoveContact(request.getPayload());
                        break;
                    case BLOCK_UNBLOCK_USER:
                        response = handleBlockUnblockUser(request.getPayload());
                        break;

                    case MY_NOTIFICATIONS:
                        response = handleGetUserNotifications();
                        break;
                    case MARK_NOTIFICATION_AS_READ:
                        response = handleMarkNotificationAsRead(request.getPayload());
                        break;
                    case DELETE_NOTIFICATION:
                        response = handleDeleteNotification(request.getPayload());
                        break;
                    case GET_FILE_BY_MEDIA:
                        response = handleGetFileByMedia(request.getPayload());
                        break;

                    case INITIATE_VIDEO_CALL:
                        response = handleInitialeVideoCall(request.getPayload());
                        break;

                    case VIDEO_CALL_ANSWER:
                        response = handlVideoCallAnser(request.getPayload());
                        break;

                    case END_VIDEO_CALL:
                        // Payload: {"target_user_id": X}
                        response = handlEndVideoCall(request.getPayload());

                        break;

                    default:
                        response = new Response(false, "Unknown command: " + request.getCommand(), null);
                }
            } catch (Exception e) {
                System.err.println("Error processing command " + request.getCommand() + ": " + e.getMessage());
                e.printStackTrace();
                response = new Response(false, "Server internal error: " + e.getMessage(), null);
            }
            out.println(response.toJson());
        }


        // --- Authentication & Core Operations ---

        private Response handleLogin(String payload) {
            Type type = new TypeToken<Map<String, String>>() {}.getType();
            Map<String, String> loginData = gson.fromJson(payload, type);
            String phoneNumber = loginData.get("phone_number");
            String password = loginData.get("password");

            Optional<User> userOptional = userDao.getUserByPhoneNumber(phoneNumber);
            if (userOptional.isPresent()) {
                User user = userOptional.get();
                if (user.getPassword().equals(password)) {
                    currentUserId = user.getId();
                    loggedInUsers.put(currentUserId, this);
                    userDao.updateUserOnlineStatus(currentUserId, true);
                    user.setPassword(null);
                    return new Response(true, "Login successful!", gson.toJson(user));
                } else {
                    return new Response(false, "Invalid credentials.", null);
                }
            } else {
                return new Response(false, "User not found.", null);
            }
        }

        private Response handleLogout() {
            if (currentUserId != -1) {
                loggedInUsers.remove(currentUserId);
                userDao.updateUserOnlineStatus(currentUserId, false);
                this.currentUserId = -1;
                return new Response(true, "Logged out successfully.", null);
            }
            return new Response(false, "No user was logged in for this session.", null);
        }

        // NEW: Unified handleSendMessage method
        private Response handleSendMessage(String payload) {
            System.out.println(payload);
            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> messageData = gson.fromJson(payload, type);

            int chatId = ((Double) messageData.get("chat_id")).intValue();
            String content = (String) messageData.get("content"); // This can be text content or caption
            //System.out.println(" ----------- " + messageData.get("media").toString());
            // Check if it's a media message (by checking for 'media' object in payload)
            boolean isMediaMessage = messageData.containsKey("media") && messageData.get("media") != null;

            try {
                if (!chatParticipantDao.isUserParticipant(chatId, currentUserId)) {
                    return new Response(false, "You are not a participant of this chat.", null);
                }

                if (isMediaMessage) {
                    // It's a media message, initiate file transfer
                    Type mediaType = new TypeToken<Media>() {}.getType();
                    Media mediaPayload = gson.fromJson(gson.toJson(messageData.get("media")), mediaType);

                    if (mediaPayload.getFileName() == null || mediaPayload.getFileName().isEmpty() || mediaPayload.getFileSize() <= 0) {
                        return new Response(false, "Missing file details (name, size) for media transfer.", null);
                    }

                    String transferId = UUID.randomUUID().toString();
                    mediaPayload.setFilePathOrUrl(transferId+"_"+mediaPayload.getFileName());
                    int mediaID = mediaDao.createMedia(mediaPayload);
                    System.out.println(" ************************* "+ mediaID);
                    FileTransferMetadata metadata = new FileTransferMetadata(
                            currentUserId, chatId, mediaPayload.getFileName(), mediaPayload.getFileSize(),
                            mediaPayload.getMediaType(), content, transferId ,mediaID // content is now the caption
                    );
                    pendingFileTransfers.put(transferId, metadata);



                    System.out.println("Server: Initiating media send for '" + mediaPayload.getFileName() + "' (transferId: " + transferId + ")");
                    return new Response(true, "READY_TO_RECEIVE_FILE", gson.toJson(Map.of("transfer_id", transferId)));

                } else {
                    // It's a text message
                    if (content == null || content.trim().isEmpty()) {
                        return new Response(false, "Message content cannot be empty.", null);
                    }

                    Message message = new Message();
                    message.setChatId(chatId);
                    message.setSenderId(currentUserId);
                    message.setContent(content);
                    message.setSentAt(LocalDateTime.now());
                    message.setViewCount(0);
                    message.setMedia(null); // Explicitly set media to null for text messages

                    int messageId = messageDao.createMessage(message);

                    if (messageId != -1) {
                        message.setId(messageId);
                        notifyChatParticipants(chatId, new Response(true, "New message received", gson.toJson(message)));
                        return new Response(true, "Message sent successfully!", gson.toJson(message));
                    } else {
                        return new Response(false, "Failed to send message.", null);
                    }
                }
            } catch (SQLException e) {
                System.err.println("Error sending message: " + e.getMessage());
                return new Response(false, "Server error sending message.", null);
            }
        }


        // --- Helper for broadcasting messages ---
        public  void notifyChatParticipants(int chatId, Response notificationResponse) {
            try {
                List<ChatParticipant> participants = chatParticipantDao.getChatParticipants(chatId);
                for (ChatParticipant participant : participants) {
                    ClientHandler2 handler = loggedInUsers.get(participant.getUserId());
                    if (handler != null && handler.currentUserId != currentUserId) { // Don't send to self (sender)
                        handler.out.println(notificationResponse.toJson());
                    }
                }
            } catch (Exception e) {
                System.err.println("Error notifying chat participants: " + e.getMessage());
            }
        }

        // live call
       // INITIATE_VIDEO_CALL
        //        handleGetFileByMedia
        // --- Helper for broadcasting messages ---
        public  Response  handleInitialeVideoCall(String payload) {

            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> callRequestPayload = gson.fromJson(payload, type);

            if (callRequestPayload != null) {

                int targetUserId = Integer.parseInt(callRequestPayload.get("target_user_id").toString());
                this.clientPublicIp = (String) callRequestPayload.get("sender_public_ip");
                this.clientUdpPort =(int) Double.parseDouble(callRequestPayload.get("sender_udp_port").toString());

                // Store client's UDP info on the server (important for P2P connection exchange)
                userPublicIPs.put(currentUserId, clientPublicIp);
                userUdpPorts.put(currentUserId, clientUdpPort);

                ClientHandler2 targetHandler = loggedInUsers.get(targetUserId);


                System.out.println("the target id is "+targetUserId);
                if (targetHandler != null && targetHandler.currentUserId != -1) {
                    // Notify target client about incoming call
                    Map<String, Object> offerData = new HashMap<>();
                    offerData.put("caller_id", currentUserId);
                    offerData.put("caller_username", userDao.getUserById(currentUserId).get().getUsername());
                    offerData.put("caller_public_ip", this.clientPublicIp);
                    offerData.put("caller_udp_port", this.clientUdpPort);
                    //targetHandler.out.println(new Response(true, "Message sent successfully!", null));
                    Response response =new Response(true, "VIDEO_CALL_OFFER", gson.toJson(offerData));
                    targetHandler.out.println(response.toJson());
                    //targetHandler.out.println();
                    System.out.println("this ip "+currentUserId+" call this ip "+targetUserId);
                    activeVideoCalls.put(currentUserId, targetUserId); // Mark call as pending
                    activeVideoCalls.put(targetUserId, currentUserId); // Bidirectional mapping
                    return new Response(true, "VIDEO_CALL_INITIATED", null); // Acknowledge to caller
                } else {
                    return new Response(false, "Recipient offline or not found.", null);
                }
            } else {
                return new Response(false, "Invalid video call initiation request payload.", null);
            }
            //return new Response(false, "Invalid video call initiation request payload.", null);
        }

        public  Response  handlVideoCallAnser(String payload) {


                Type type = new TypeToken<Map<String, Object>>() {}.getType();
                Map<String, Object> answerPayload = gson.fromJson(payload, type);


            if (answerPayload != null) {
                int callerId = ((Double) answerPayload.get("caller_id")).intValue();
                boolean accepted = (Boolean) answerPayload.get("accepted");

                // Callee also stores its UDP info
                this.clientPublicIp = (String) answerPayload.get("recipient_public_ip");
                this.clientUdpPort = ((Double) answerPayload.get("recipient_udp_port")).intValue();
                userPublicIPs.put(currentUserId, clientPublicIp);
                userUdpPorts.put(currentUserId, clientUdpPort);

                ClientHandler2 callerHandler = loggedInUsers.get(callerId);
                if (callerHandler != null && callerHandler.currentUserId != -1) {
                    Map<String, Object> responseData = new HashMap<>();
                    responseData.put("callee_id", currentUserId);
                    responseData.put("callee_username", userDao.getUserById(currentUserId).get().getUsername());

                    if (accepted) {
                        responseData.put("callee_public_ip", this.clientPublicIp);
                        responseData.put("callee_udp_port", this.clientUdpPort);
                        Response response =new Response(true, "VIDEO_CALL_ACCEPTED", gson.toJson(responseData));
                       callerHandler.out.println(response.toJson());

                        return new Response(true, "CALL_ACCEPTED", null); // Acknowledge to callee
                    } else {
                        activeVideoCalls.remove(currentUserId); // Remove pending call
                        activeVideoCalls.remove(callerId);
                        Response response = new Response(false, "VIDEO_CALL_REJECTED", gson.toJson(responseData));
                        callerHandler.out.println(response.toJson());

                        return new Response(true, "CALL_REJECTED", null); // Acknowledge to callee
                    }
                } else {
                    return new Response(false, "Caller offline or not found.", null);
                }
            } else {
                return new Response(false, "Invalid video call answer payload.", null);
            }
        }


        public  Response  handlEndVideoCall(String payload) {

                Type type = new TypeToken<Map<String, Object>>() {}.getType();
                Map<String, Object> endCallPayload = gson.fromJson(payload, type);

            if (endCallPayload != null) {
                int targetUserId = ((Double) endCallPayload.get("target_user_id")).intValue();
                ClientHandler2 targetHandler = loggedInUsers.get(targetUserId);

                activeVideoCalls.remove(currentUserId);
                activeVideoCalls.remove(targetUserId);

                if (targetHandler != null) {
                    Map<String, Object> endedData = new HashMap<>();
                    endedData.put("ender_id", currentUserId);
                    Response response = new Response(true, "VIDEO_CALL_ENDED", gson.toJson(endedData));
                    targetHandler.out.println(response.toJson());
                }
                return new Response(true, "CALL_ENDED", null);
            } else {
                return new Response(false, "Invalid end call request.", null);
            }
        }

        // --- Existing and New Command Implementations (User Management) ---

        private Response handleRegister(String payload) {
            User newUser = gson.fromJson(payload, User.class);

            if (newUser.getPhoneNumber() == null || newUser.getPhoneNumber().isEmpty() ||
                    newUser.getPassword() == null || newUser.getPassword().isEmpty() ||
                    newUser.getFirstName() == null || newUser.getFirstName().isEmpty()) {
                return new Response(false, "Missing required registration fields.", null);
            }

            if (userDao.getUserByPhoneNumber(newUser.getPhoneNumber()).isPresent()) {
                return new Response(false, "Phone number already registered.", null);
            }

            int userId = userDao.createUser(newUser);
            if (userId != -1) {
                newUser.setId(userId);
                newUser.setPassword(null);
                return new Response(true, "Registration successful!", gson.toJson(newUser));
            } else {
                return new Response(false, "Failed to register user.", null);
            }
        }

        private Response handleGetUserProfile(String payload) {
            Type type = new TypeToken<Map<String, Double>>() {}.getType();
            Map<String, Double> params = gson.fromJson(payload, type);
            int targetUserId = params.get("userId").intValue();

            Optional<User> userOptional = userDao.getUserById(targetUserId);
            if (userOptional.isPresent()) {
                User user = userOptional.get();
                user.setPassword(null);
                return new Response(true, "User profile retrieved.", gson.toJson(user));
            } else {
                return new Response(false, "User not found.", null);
            }
        }

        private Response handleUpdateUserProfile(String payload) {
            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> updates = gson.fromJson(payload, type);
            int userIdToUpdate = ((Double) updates.get("userId")).intValue();

            if (userIdToUpdate != currentUserId) {
                return new Response(false, "Unauthorized: You can only update your own profile.", null);
            }

            User existingUser = userDao.getUserById(currentUserId).orElse(null);
            if (existingUser == null) {
                return new Response(false, "User not found for update.", null);
            }

            if (updates.containsKey("first_name")) {
                existingUser.setFirstName((String) updates.get("first_name"));
            }
            if (updates.containsKey("last_name")) {
                existingUser.setLastName((String) updates.get("last_name"));
            }
            if (updates.containsKey("bio")) {
                existingUser.setBio((String) updates.get("bio"));
            }
            if (updates.containsKey("profile_picture_url")) {
                String url = (String) updates.get("profile_picture_url");
                existingUser.setProfilePictureUrl(url != null && url.isEmpty() ? null : url);
            }

            boolean success = userDao.updateUser(existingUser);
            if (success) {
                existingUser.setPassword(null);
                return new Response(true, "Profile updated successfully!", gson.toJson(existingUser));
            } else {
                return new Response(false, "Failed to update profile.", null);
            }
        }

        private Response handleDeleteUser(String payload) {
            Type type = new TypeToken<Map<String, Double>>() {}.getType();
            Map<String, Double> params = gson.fromJson(payload, type);
            int targetUserId = params.get("userId").intValue();

            if (targetUserId != currentUserId) {
                return new Response(false, "Unauthorized: You can only delete your own account.", null);
            }

            boolean success = userDao.deleteUser(targetUserId);
            if (success) {
                loggedInUsers.remove(currentUserId);
                userDao.updateUserOnlineStatus(currentUserId, false);
                this.currentUserId = -1;
                return new Response(true, "User account deleted successfully.", null);
            } else {
                return new Response(false, "Failed to delete user account.", null);
            }
        }

        private Response handleGetAllUsers() {
            List<User> users = userDao.getAllUsers();
            users.forEach(u -> u.setPassword(null));
            return new Response(true, "All users retrieved.", gson.toJson(users));
        }


        // --- Chat Management ---

        private Response handleCreateChat(String payload) {
            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> chatData = gson.fromJson(payload, type);

            String chatType = (String) chatData.get("chat_type");
            String chatName = (String) chatData.get("chat_name");
            String chatDescription = (String) chatData.get("chat_description");
            String publicLink = (String) chatData.get("public_link");

            Chat newChat = new Chat();
            newChat.setChatType(chatType);
            newChat.setChatName(chatName);
            newChat.setChatDescription(chatDescription);
            newChat.setPublicLink(publicLink);
            newChat.setCreatorId(currentUserId);
            newChat.setCreatedAt(LocalDateTime.now());

            int chatId = chatDao.createChat(newChat);
            if (chatId != -1) {
                newChat.setId(chatId);
                ChatParticipant creatorParticipant = new ChatParticipant();
                creatorParticipant.setChatId(chatId);
                creatorParticipant.setUserId(currentUserId);
                creatorParticipant.setRole("creator");
                creatorParticipant.setJoinedAt(LocalDateTime.now());
                chatParticipantDao.createChatParticipant(creatorParticipant);

                return new Response(true, "Chat created successfully!", gson.toJson(newChat));
            } else {
                return new Response(false, "Failed to create chat.", null);
            }
        }

        private Response handleGetChatMessages(String payload) {
            Type type = new TypeToken<Map<String, Double>>() {}.getType();
            Map<String, Double> params = gson.fromJson(payload, type);
            int chatId = params.get("chat_id").intValue();
            int limit = params.get("limit").intValue();
            int offset = params.get("offset").intValue();

            try {
                if (!chatParticipantDao.isUserParticipant(chatId, currentUserId)) {
                    return new Response(false, "You are not a participant of this chat.", null);
                }

                List<Message> messages = messageDao.getChatMessages(chatId, limit, offset);

                for (Message msg : messages) {
                    if (msg.getMediaId() != null){
                        Media media =new Media();
                        msg.setMedia( mediaDao.getMediaById(msg.getMediaId().intValue()).get() );
//                        System.out.println(media.toString());
                    }
                    if (msg.getSenderId() != currentUserId) {
                        messageDao.incrementViewCount(msg.getId());
                    }
                    //notificationDao.markMessageNotificationsAsRead(currentUserId, msg.getId());
                }

                return new Response(true, "Messages retrieved.", gson.toJson(messages));
            } catch (SQLException e) {
                System.err.println("Error getting chat messages: " + e.getMessage());
                return new Response(false, "Server error retrieving messages.", null);
            }
        }

        private Response handleGetUserChats() {
            try {
                // Get all chats the current user is a participant of
                // ChatDao chatDao = new ChatDao(); // Already defined as a member of ChatServer2, accessible via ChatServer2.this.chatDao
                List<Chat> chats = chatDao.getUserChats(currentUserId);
                return new Response(true, "User chats retrieved.", gson.toJson(chats));
            } catch (SQLException e) {
                System.err.println("Error getting user chats: " + e.getMessage());
                return new Response(false, "Server error retrieving user chats.", null);
            }
        }

        private Response handleGetChatDetails(String payload) {
            Type type = new TypeToken<Map<String, Double>>() {}.getType();
            Map<String, Double> params = gson.fromJson(payload, type);
            int chatId = params.get("chat_id").intValue();

            try {
                if (!chatParticipantDao.isUserParticipant(chatId, currentUserId)) {
                    return new Response(false, "You are not a participant of this chat.", null);
                }

                Optional<Chat> chatOptional = chatDao.getChatById(chatId);
                if (chatOptional.isPresent()) {
                    return new Response(true, "Chat details retrieved.", gson.toJson(chatOptional.get()));
                } else {
                    return new Response(false, "Chat not found.", null);
                }
            } catch (SQLException e) {
                System.err.println("Error getting chat details: " + e.getMessage());
                return new Response(false, "Server error retrieving chat details.", null);
            }
        }

        private Response handleUpdateChat(String payload) {
            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> chatData = gson.fromJson(payload, type);

            int chatId = ((Double) chatData.get("chat_id")).intValue();
            String chatName = (String) chatData.get("chat_name");
            String chatDescription = (String) chatData.get("chat_description");
            String publicLink = (String) chatData.get("public_link");
            String chatType = (String) chatData.get("chat_type");

            try {
                Optional<Chat> existingChatOpt = chatDao.getChatById(chatId);
                if (!existingChatOpt.isPresent()) {
                    return new Response(false, "Chat not found.", null);
                }
                Chat existingChat = existingChatOpt.get();

                ChatParticipant currentUserParticipant = chatParticipantDao.getChatParticipant(chatId, currentUserId).orElse(null);
                if (currentUserParticipant == null ||
                        (!currentUserParticipant.getRole().equals("creator") && !currentUserParticipant.getRole().equals("admin"))) {
                    return new Response(false, "Unauthorized: Only chat creator or admin can update chat.", null);
                }

                if (chatName != null) existingChat.setChatName(chatName.isEmpty() ? null : chatName);
                if (chatDescription != null) existingChat.setChatDescription(chatDescription.isEmpty() ? null : chatDescription);
                if (publicLink != null) existingChat.setPublicLink(publicLink.isEmpty() ? null : publicLink);
                if (chatType != null) existingChat.setChatType(chatType);

                boolean success = chatDao.updateChat(existingChat);
                if (success) {
                    return new Response(true, "Chat updated successfully!", gson.toJson(existingChat));
                } else {
                    return new Response(false, "Failed to update chat.", null);
                }
            }
            catch (SQLException e) {
                System.err.println("Error updating chat: " + e.getMessage());
                return new Response(false, "Server error updating chat.", null);
            }
        }


        private Response handleDeleteChat(String payload) {
            Type type = new TypeToken<Map<String, Double>>() {}.getType();
            Map<String, Double> params = gson.fromJson(payload, type);
            int chatId = params.get("chat_id").intValue();

            Optional<Chat> chatOptional = chatDao.getChatById(chatId);
            if (!chatOptional.isPresent()) {
                return new Response(false, "Chat not found.", null);
            }
            Chat chatToDelete = chatOptional.get();

            if (chatToDelete.getCreatorId() != currentUserId) {
                return new Response(false, "Unauthorized: Only the chat creator can delete this chat.", null);
            }

            boolean success = chatDao.deleteChat(chatId);
            if (success) {
                return new Response(true, "Chat deleted successfully!", null);
            } else {
                return new Response(false, "Failed to delete chat.", null);
            }
        }


        // --- Message Management ---

        private Response handleUpdateMessage(String payload) {
            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> messageData = gson.fromJson(payload, type);
            int messageId = ((Double) messageData.get("message_id")).intValue();
            String newContent = (String) messageData.get("content");

            try {
                Optional<Message> msgOptional = messageDao.getMessageById(messageId);
                if (!msgOptional.isPresent()) {
                    return new Response(false, "Message not found.", null);
                }
                Message message = msgOptional.get();

                if (message.getSenderId() != currentUserId) {
                    return new Response(false, "Unauthorized: You can only update your own messages.", null);
                }
                // Only allow updating content if it's a text message or a media message with caption
                if (message.getMedia() != null && newContent != null) {
                    // It's a media message, update its caption
                    message.setContent(newContent);
                } else if (message.getMedia() == null && newContent != null) {
                    // It's a text message, update its content
                    message.setContent(newContent);
                } else if (message.getMedia() == null && newContent == null) {
                    return new Response(false, "New message content cannot be empty for text message.", null);
                } else { // Trying to update media content with null for media message
                    return new Response(false, "Cannot update media content directly.", null);
                }


                boolean success = messageDao.updateMessage(message);
                if (success) {
                    // Optionally notify chat participants about message update
                    // notifyChatParticipants(message.getChatId(), new Response(true, "Message updated", gson.toJson(message)));
                    return new Response(true, "Message updated successfully!", null);
                } else {
                    return new Response(false, "Failed to update message.", null);
                }
            } catch (SQLException e) {
                System.err.println("Error updating message: " + e.getMessage());
                return new Response(false, "Server error updating message.", null);
            }
        }

        private Response handleDeleteMessage(String payload) {
            Type type = new TypeToken<Map<String, Double>>() {}.getType();
            Map<String, Double> params = gson.fromJson(payload, type);
            int messageId = params.get("message_id").intValue();

            try {
                Optional<Message> msgOptional = messageDao.getMessageById(messageId);
                if (!msgOptional.isPresent()) {
                    return new Response(false, "Message not found.", null);
                }
                Message message = msgOptional.get();

                ChatParticipant currentUserParticipant = chatParticipantDao.getChatParticipant(message.getChatId(), currentUserId).orElse(null);
                boolean isChatAdminOrCreator = (currentUserParticipant != null &&
                        (currentUserParticipant.getRole().equals("admin") || currentUserParticipant.getRole().equals("creator")));

                if (message.getSenderId() != currentUserId && !isChatAdminOrCreator) {
                    return new Response(false, "Unauthorized: Only the sender or a chat admin/creator can delete this message.", null);
                }

                boolean success = messageDao.deleteMessage(messageId);
                if (success) {
                    // Optionally, if it was a media message, delete the file from storage
                    if (message.getMedia() != null ) {
                        //FileStorageManager.deleteFile(message.getMedia().getId() + "_" + message.getMedia().getFileName());
                    }
                    return new Response(true, "Message deleted successfully!", null);
                } else {
                    return new Response(false, "Failed to delete message.", null);
                }
            } catch (SQLException e) {
                System.err.println("Error deleting message: " + e.getMessage());
                return new Response(false, "Server error deleting message.", null);
            }
        }

        private Response handleMarkMessageAsRead(String payload) {
            Type type = new TypeToken<Map<String, Double>>() {}.getType();
            Map<String, Double> params = gson.fromJson(payload, type);
            int messageId = params.get("message_id").intValue();

            try {
                Optional<Message> msgOptional = messageDao.getMessageById(messageId);
                if (!msgOptional.isPresent()) {
                    return new Response(false, "Message not found.", null);
                }
                Message message = msgOptional.get();

                if (!chatParticipantDao.isUserParticipant(message.getChatId(), currentUserId)) {
                    return new Response(false, "You are not a participant of this chat.", null);
                }
                if (message.getSenderId() != currentUserId) {
                    messageDao.incrementViewCount(messageId);
                    //notificationDao.markMessageNotificationsAsRead(currentUserId, messageId);
                }
                return new Response(true, "Message marked as read!", null);
            } catch (SQLException e) {
                System.err.println("Error marking message as read: " + e.getMessage());
                return new Response(false, "Server error marking message as read.", null);
            }
        }

        // --- Chat Participant Management ---


        private Response handleAddChatParticipant(String payload) {
            try {
                // The payload contains the chat ID, user ID to add, and their role
                Type type = new TypeToken<Map<String, Object>>() {}.getType();
                Map<String, Object> data = gson.fromJson(payload, type);

                int chatId = ((Double) data.get("chat_id")).intValue();
                int userIdToAdd = ((Double) data.get("user_id")).intValue();
                String role = (String) data.get("role");

                //System.out.println("caht id" + chatId + " user id "+userIdToAdd + "and rowl" + role);
                // Basic validation: chat exists, target user exists
                // ChatDao chatDao = new ChatDao(); // Already defined
                if (!chatDao.getChatById(chatId).isPresent()) {
                    return new Response(false, "Chat not found.", null);
                }
                // UserDao userDao = new UserDao(); // Already defined
                if (!userDao.getUserById(userIdToAdd).isPresent()) {
                    return new Response(false, "Target user to add not found.", null);
                }

                // Authorization: Only chat creator/admin can add participants
                // ChatParticipantDao cpDao = new ChatParticipantDao(); // Already defined
                //
                System.out.println("user id" + currentUserId);
                Optional<ChatParticipant> currentUserAsParticipant = chatParticipantDao.getChatParticipant(currentUserId , chatId);
                System.out.println(currentUserAsParticipant.get().getUserId() + currentUserAsParticipant.get().getRole());

                if (currentUserAsParticipant.isEmpty() ||
                        (!"creator".equalsIgnoreCase(currentUserAsParticipant.get().getRole()) &&
                                !"admin".equalsIgnoreCase(currentUserAsParticipant.get().getRole()))) {
                    return new Response(false, "Unauthorized: Only chat creators or admins can add participants.", null);
                }

                // Prevent adding a user who is already a participant
                if (chatParticipantDao.isUserParticipant(chatId, userIdToAdd)) {
                    return new Response(false, "User is already a participant in this chat.", null);
                }

                ChatParticipant newParticipant = new ChatParticipant();
                newParticipant.setChatId(chatId);
                newParticipant.setUserId(userIdToAdd);
                newParticipant.setRole(role != null ? role : "member"); // Default to 'member' if no role provided
                newParticipant.setJoinedAt(LocalDateTime.now());

                int participantId = chatParticipantDao.createChatParticipant(newParticipant);
                if (participantId != -1) {
                    newParticipant.setId(participantId);
                    // Optionally notify the added user or other participants
                    return new Response(true, "Participant added successfully!", gson.toJson(newParticipant));
                } else {
                    return new Response(false, "Failed to add participant.", null);
                }
            } catch (SQLException e) {
                System.err.println("Error adding chat participant: " + e.getMessage());
                return new Response(false, "Server error adding participant.", null);
            }
        }


        private Response handleGetChatParticipants(String payload) {
            Type type = new TypeToken<Map<String, Double>>() {}.getType();
            Map<String, Double> params = gson.fromJson(payload, type);
            int chatId = params.get("chat_id").intValue();

            try {
                if (!chatParticipantDao.isUserParticipant(chatId, currentUserId)) {
                    return new Response(false, "You are not a participant of this chat.", null);
                }
                List<ChatParticipant> participants = chatParticipantDao.getChatParticipants(chatId);
                return new Response(true, "Chat participants retrieved.", gson.toJson(participants));
            } catch (SQLException e) {
                System.err.println("Error getting chat participants: " + e.getMessage());
                return new Response(false, "Server error retrieving participants.", null);
            }
        }

        private Response handleUpdateChatParticipantRole(String payload) {
            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> params = gson.fromJson(payload, type);
            int chatId = ((Double) params.get("chat_id")).intValue();
            int targetUserId = ((Double) params.get("user_id")).intValue();
            String newRole = (String) params.get("new_role");

            try {

                ChatParticipant currentUserParticipant = chatParticipantDao.getChatParticipant(currentUserId , chatId).orElse(null);
                if (currentUserParticipant == null ||
                        (!"creator".equalsIgnoreCase(currentUserParticipant.getRole()) &&
                                !"admin".equalsIgnoreCase(currentUserParticipant.getRole()))) {
                    return new Response(false, "Unauthorized: Only chat creators or admins can add participants.", null);
                }

                if (targetUserId == currentUserId && !currentUserParticipant.getRole().equals("creator")) {
                    return new Response(false, "You cannot change your own role unless you are the creator.", null);
                }
                Chat chat = chatDao.getChatById(chatId).orElse(null);
                if (chat != null && chat.getCreatorId() == targetUserId && !newRole.equals("creator")) {
                    return new Response(false, "Cannot change the creator's role to something other than 'creator'.", null);
                }

                System.out.println(newRole);
                boolean success = chatParticipantDao.updateParticipantRole(chatId, targetUserId, newRole);
                if (success) {
                    return new Response(true, "Participant role updated successfully!", null);
                } else {
                    return new Response(false, "Failed to update participant role.", null);
                }
            } catch (SQLException e) {
                System.err.println("Error updating chat participant role: " + e.getMessage());
                return new Response(false, "Server error updating participant role.", null);
            }
        }

        private Response handleRemoveChatParticipant(String payload) {
            Type type = new TypeToken<Map<String, Double>>() {}.getType();
            Map<String, Double> params = gson.fromJson(payload, type);
            int chatId = params.get("chat_id").intValue();
            int userIdToRemove = params.get("user_id").intValue();

            try {


                ChatParticipant currentUserParticipant = chatParticipantDao.getChatParticipant(currentUserId , chatId).orElse(null);
                boolean isChatAdminOrCreator = (currentUserParticipant != null &&
                        (currentUserParticipant.getRole().equals("admin") || currentUserParticipant.getRole().equals("creator")));

                if (userIdToRemove != currentUserId && !isChatAdminOrCreator) {
                    return new Response(false, "Unauthorized: Only chat creator/admin can remove other participants.", null);
                }

                Chat chat = chatDao.getChatById(chatId).orElse(null);
                if (chat != null && chat.getCreatorId() == userIdToRemove) {
                    return new Response(false, "The chat creator cannot be removed from the chat.", null);
                }

                boolean success = chatParticipantDao.deleteChatParticipant(chatId, userIdToRemove);
                if (success) {
                    return new Response(true, "Participant removed successfully!", null);
                } else {
                    return new Response(false, "Failed to remove participant.", null);
                }
            } catch (SQLException e) {
                System.err.println("Error removing chat participant: " + e.getMessage());
                return new Response(false, "Server error removing participant.", null);
            }
        }

        // --- Contact Management ---

        private Response handleAddContact(String payload) {
            Type type = new TypeToken<Map<String, Double>>() {}.getType();
            Map<String, Double> params = gson.fromJson(payload, type);
            int contactUserId = params.get("contact_user_id").intValue();

            if (contactUserId == currentUserId) {
                return new Response(false, "Cannot add yourself as a contact.", null);
            }

            if (!userDao.getUserById(contactUserId).isPresent()) {
                return new Response(false, "Contact user not found.", null);
            }
            if (contactDao.isUserContact(currentUserId, contactUserId)) {
                return new Response(false, "User is already in your contacts.", null);
            }

            boolean success = false ; // contactDao.addContact(currentUserId, contactUserId);
            if (success) {
                return new Response(true, "Contact added successfully!", null);
            } else {
                return new Response(false, "Failed to add contact.", null);
            }
        }

        private Response handleGetContacts() {
            List<User> contacts = contactDao.getContactsForUser(currentUserId);
            contacts.forEach(u -> u.setPassword(null));
            return new Response(true, "User contacts retrieved.", gson.toJson(contacts));
        }

        private Response handleRemoveContact(String payload) {
            Type type = new TypeToken<Map<String, Double>>() {}.getType();
            Map<String, Double> params = gson.fromJson(payload, type);
            int contactUserId = params.get("contact_user_id").intValue();

            boolean success = contactDao.deleteContact(currentUserId, contactUserId);
            if (success) {
                return new Response(true, "Contact removed successfully!", null);
            } else {
                return new Response(false, "Failed to remove contact. It might not exist.", null);
            }
        }

        private Response handleBlockUnblockUser(String payload) {
            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> params = gson.fromJson(payload, type);
            int targetUserId = ((Double) params.get("target_user_id")).intValue();
            String action = (String) params.get("action");

            if (targetUserId == currentUserId) {
                return new Response(false, "Cannot block/unblock yourself.", null);
            }
            if (!userDao.getUserById(targetUserId).isPresent()) {
                return new Response(false, "Target user not found.", null);
            }

            boolean success;
            if ("block".equalsIgnoreCase(action)) {
                success = false ; // contactDao.blockUser(currentUserId, targetUserId);
                if (success) {
                    return new Response(true, "User blocked successfully!", null);
                } else {
                    return new Response(false, "Failed to block user. User might already be blocked.", null);
                }
            } else if ("unblock".equalsIgnoreCase(action)) {
                success = false ; //  contactDao.unblockUser(currentUserId, targetUserId);
                if (success) {
                    return new Response(true, "User unblocked successfully!", null);
                } else {
                    return new Response(false, "Failed to unblock user. User might not be blocked.", null);
                }
            } else {
                return new Response(false, "Invalid action. Use 'block' or 'unblock'.", null);
            }
        }

        // --- Notification Management ---

        private Response handleGetUserNotifications() {
            try {
                List<Notification> notifications = notificationDao.getNotificationsByUserId(currentUserId);
                return new Response(true, "User notifications retrieved.", gson.toJson(notifications));
            } catch (SQLException e) {
                System.err.println("Error getting user notifications: " + e.getMessage());
                return new Response(false, "Server error retrieving notifications.", null);
            }
        }

        private Response handleMarkNotificationAsRead(String payload) {
            Type type = new TypeToken<Map<String, Double>>() {}.getType();
            Map<String, Double> params = gson.fromJson(payload, type);
            int notificationId = params.get("notification_id").intValue();

            try {
                Optional<Notification> notificationOptional = notificationDao.getNotificationById(notificationId);
                if (!notificationOptional.isPresent() || notificationOptional.get().getId() != currentUserId) {
                    return new Response(false, "Notification not found or unauthorized.", null);
                }
                boolean success = notificationDao.markNotificationAsRead(notificationId);
                if (success) {
                    return new Response(true, "Notification marked as read!", null);
                } else {
                    return new Response(false, "Failed to mark notification as read.", null);
                }
            } catch (SQLException e) {
                System.err.println("Error marking notification as read: " + e.getMessage());
                return new Response(false, "Server error marking notification as read.", null);
            }
        }

        private Response handleDeleteNotification(String payload) {
            Type type = new TypeToken<Map<String, Double>>() {}.getType();
            Map<String, Double> params = gson.fromJson(payload, type);
            int notificationId = params.get("notification_id").intValue();

            try {
                Optional<Notification> notificationOptional = notificationDao.getNotificationById(notificationId);
                if (!notificationOptional.isPresent() || notificationOptional.get().getId() != currentUserId) {
                    return new Response(false, "Notification not found or unauthorized.", null);
                }
                boolean success = notificationDao.deleteNotification(notificationId);
                if (success) {
                    return new Response(true, "Notification deleted successfully!", null);
                } else {
                    return new Response(false, "Failed to delete notification.", null);
                }
            } catch (SQLException e) {
                System.err.println("Error deleting notification: " + e.getMessage());
                return new Response(false, "Server error deleting notification.", null);
            }
        }
    }



    private class FileDownloadHandler implements Runnable {
        private Socket fileSocket;
        private String fileName;
        private String mediaId; // This is the UUID part of the file name on the server

        public FileDownloadHandler(Socket fileSocket, String fileName, String mediaId) {
            this.fileSocket = fileSocket;
            this.fileName = fileName;
            this.mediaId = mediaId;
        }

        @Override
        public void run() {
            System.out.println(" ---------------- sending -----------------------  "+ mediaId + " "+ fileName);
            String filePathOnServer = FileStorageManager.getUploadDirectory() + File.separator + fileName;
            System.out.println(filePathOnServer);
            File fileToSend = new File(filePathOnServer);

            if (!fileToSend.exists() || !fileToSend.isFile()) {
                System.err.println("File not found on server for download: " + filePathOnServer);
                try {
                    PrintWriter fileOut = new PrintWriter(fileSocket.getOutputStream(), true);
                    fileOut.println("File not found on ");
                    fileSocket.close();
                } catch (IOException e) {
                    System.err.println("Error sending file not found message: " + e.getMessage());
                }
                return;
            }

            try (OutputStream os = fileSocket.getOutputStream();
                 FileInputStream fis = new FileInputStream(fileToSend)) {

                byte[] buffer = new byte[4096];
                int bytesRead;
                long totalBytesSent = 0;
                long fileSize = fileToSend.length();

                System.out.println("Sending file: " + fileName + " (" + fileSize + " bytes)");

                while ((bytesRead = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                    totalBytesSent += bytesRead;
                }
                os.flush();

                System.out.println("File '" + fileName + "' sent successfully! Total bytes: " + totalBytesSent);

            } catch (IOException e) {
                System.err.println("Error during file download: " + e.getMessage());
                e.printStackTrace();
            } finally {
                try {
                    if (fileSocket != null && !fileSocket.isClosed()) {
                        fileSocket.close();
                    }
                } catch (IOException e) {
                    System.err.println("Error closing file socket: " + e.getMessage());
                }
            }
        }
    }



    private Response handleGetFileByMedia(String payload) {
        Type type = new TypeToken<Map<String, String>>() {}.getType();
        Map<String, String> data = gson.fromJson(payload, type);

        String mediaId = data.get("mediaId");
        //String fileName = data.get("fileName");
        Media mediaToTransfer = new Media() ;
        mediaToTransfer = mediaDao.getMediaById(Integer.parseInt(mediaId)).get() ;
        String fileName = mediaToTransfer.getFilePathOrUrl();

        System.out.println("------------- media id is "+ mediaId);
        if (mediaId == null || fileName == null) {
            return new Response(false, "Missing media ID or file name.", null);
        }

        // Construct the full file path on the server
        //String filePathOnServer = FileStorageManager.getUploadDirectory() + File.separator + mediaId + "_" + fileName;
        String filePathOnServer = FileStorageManager.getUploadDirectory() + File.separator + fileName;
        File fileToDownload = new File(filePathOnServer);

        if (!fileToDownload.exists() || !fileToDownload.isFile()) {
            System.err.println("Server: Client requested file " + filePathOnServer + " but it does not exist.");
            return new Response(false, "File not found on ", null);
        }

        // Store the download request metadata
        FileTransferMetadata downloadMetadata = new FileTransferMetadata(
                -1, -1, fileName, fileToDownload.length(), mediaToTransfer.getMediaType(), null, mediaToTransfer.getFileName() ,mediaToTransfer.getId());

        pendingFileDownloads.put(mediaId, downloadMetadata);

        // Respond to the client on the main socket, telling it to connect for the file
        Map<String, Object> responseData = new HashMap<>();
        responseData.put("transfer_id", mediaId);
        responseData.put("fileSize", fileToDownload.length());
        System.out.println("\nREADY_TO_SEND_FILE\n");
        return new Response(true, "READY_TO_SEND_FILE", gson.toJson(responseData));
    }




    public void main(String[] args) {
        System.out.println("Initializing database...");
        //DatabaseManager.initializeDatabase();
        System.out.println("Database initialized.");

        ChatServer2 server = new ChatServer2();
        start();
    }
}