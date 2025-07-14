// src/orgs/server/ChatServer2.java
package orgs.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import orgs.dao.*;
import orgs.model.*;
import orgs.protocol.*;
import orgs.protocol.Request;
import orgs.protocol.Response;
import orgs.utils.FileStorageManager;
import orgs.utils.LocalDateTimeAdapter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Type;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static orgs.protocol.Command.*;


public class ChatServer2 {
    private static final int PORT = 7373;
    private static final int FILE_TRANSFER_PORT = 7374; // New constant for file transfer port
    private ExecutorService commandPool = Executors.newFixedThreadPool(10); // Pool for command clients
    private ExecutorService fileTransferPool = Executors.newCachedThreadPool(); // Pool for file transfers

    private UserDao userDao = new UserDao();
    private MessageDao messageDao = new MessageDao(); // Needed for saving media messages
    private ChatDao chatDao = new ChatDao(); // Needed for chat operations
    private ChatParticipantDao chatParticipantDao = new ChatParticipantDao(); // Needed for participant operations
    private ContactDao contactDao = new ContactDao();
    private NotificationDao notificationDao = new NotificationDao();

    private Gson gson = new GsonBuilder()
            .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeAdapter())
            .serializeNulls()
            .create();

    private static final Map<Integer, ClientHandler> loggedInUsers = new ConcurrentHashMap<>();

    // NEW: Map to temporarily store pending file transfer details
    // Key: unique transferId (UUID string), Value: FileTransferMetadata
    private final ConcurrentHashMap<String,FileTransferMetadata> pendingFileTransfers = new ConcurrentHashMap<>();


    // NEW: Helper class to hold file transfer metadata
    private static class FileTransferMetadata {
        int senderId;
        int chatId;
        String fileName;
        long fileSize;
        String mediaType;
        String caption;

        public FileTransferMetadata(int senderId, int chatId, String fileName, long fileSize, String mediaType, String caption) {
            this.senderId = senderId;
            this.chatId = chatId;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.mediaType = mediaType;
            this.caption = caption;
        }
    }

    public ChatServer2() {
        // Ensure upload directory exists
        FileStorageManager.createUploadDirectory();
    }

    public void start() {
        // Start the main command server listener
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(PORT)) {
                System.out.println("Chat Server started on command port " + PORT);
                while (true) {
                    Socket clientSocket = serverSocket.accept();
                    System.out.println("New client connected (command): " + clientSocket.getInetAddress().getHostAddress());
                    ClientHandler clientHandler = new ClientHandler(clientSocket);
                    commandPool.execute(clientHandler);
                }
            } catch (IOException e) {
                System.err.println("Command Server error: " + e.getMessage());
                e.printStackTrace();
            } finally {
                commandPool.shutdown();
            }
        }, "CommandServerListener").start();






        // Start a separate server listener for file transfers
        new Thread(() -> {
            try (ServerSocket fileTransferServerSocket = new ServerSocket(FILE_TRANSFER_PORT)) {
                System.out.println("File Transfer Server started on port " + FILE_TRANSFER_PORT);
                while (true) {
                    Socket fileClientSocket = fileTransferServerSocket.accept();
                    System.out.println("New client connected (file transfer): " + fileClientSocket.getInetAddress().getHostAddress());

                    // --- Start of new logic for handling file transfer connections ---
                    try {
                        // Read the transferId sent by the client as the first data on this socket
                        BufferedReader fileIn = new BufferedReader(new InputStreamReader(fileClientSocket.getInputStream()));
                        String transferId = fileIn.readLine(); // Client sends UUID as first line
                        System.out.println();
                        if (transferId == null || transferId.isEmpty()) {
                            System.err.println("File transfer: Received empty or null transferId from " + fileClientSocket.getInetAddress());
                            fileClientSocket.close();
                            continue;
                        }

                        FileTransferMetadata metadata = pendingFileTransfers.get(transferId); // Remove after retrieving
                        pendingFileTransfers.remove(transferId);
                        if (metadata == null) {
                            System.err.println("File transfer: No pending metadata found for transferId: " + transferId + " from " + fileClientSocket.getInetAddress());
                            fileClientSocket.close();
                            continue;
                        }

                        System.out.println("File transfer: Initiating transfer for file " + metadata.fileName + " (transferId: " + transferId + ")");
                        // Now, pass the fileSocket and retrieved metadata to the FileTransferHandler
                        fileTransferPool.execute(new FileTransferHandler(
                                fileClientSocket,
                                metadata.senderId,
                                metadata.chatId,
                                metadata.fileName,
                                metadata.fileSize,
                                metadata.mediaType,
                                metadata.caption,
                                messageDao // Pass messageDao to save metadata after transfer
                        ));

                    } catch (IOException e) {
                        System.err.println("Error setting up file transfer handler for " + fileClientSocket.getInetAddress() + ": " + e.getMessage());
                        try {
                            fileClientSocket.close();
                        } catch (IOException ex) {
                            System.err.println("Error closing file client socket: " + ex.getMessage());
                        }
                    }
                    // --- End of new logic ---
                }
            } catch (IOException e) {
                System.err.println("File Transfer Server error: " + e.getMessage());
                e.printStackTrace();
            } finally {
                fileTransferPool.shutdown();
            }
        }, "FileTransferServerListener").start();
    }

    private class ClientHandler implements Runnable {
        private Socket clientSocket;
        private PrintWriter out;
        private BufferedReader in;
        private int currentUserId = -1; // To store the ID of the logged-in user for this handler

        // Temporarily store file transfer details if a file is about to be sent
        private String pendingFileTransferFileName;
        private long pendingFileTransferFileSize;
        private String pendingFileTransferMediaType;
        private String pendingFileTransferCaption;
        private int pendingFileTransferChatId; // For media messages

        public ClientHandler(Socket socket) {
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
                    //System.out.println("Received from client " + clientSocket.getInetAddress().getHostAddress() + ": " + clientRequestJson);
                    Request request = gson.fromJson(clientRequestJson, Request.class);
                    processRequest(request);
                }
            } catch (IOException e) {
                if (currentUserId != -1) {
                    System.out.println("Client " + currentUserId + " disconnected.");
                    loggedInUsers.remove(currentUserId);
                    userDao.updateUserOnlineStatus(currentUserId, false); // Mark user offline
                } else {
                    System.out.println("Client disconnected unexpectedly: " + clientSocket.getInetAddress().getHostAddress() + " - " + e.getMessage());
                }
            } finally {
                try {
                    if (currentUserId != -1) {
                        loggedInUsers.remove(currentUserId);
                        userDao.updateUserOnlineStatus(currentUserId, false); // Ensure offline status on exit
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
                    case SEND_TEXT_MESSAGE: // New specific command for text
                        response = handleSendTextMessage(request.getPayload());
                        break;
                    case SEND_IMAGE:
                    case SEND_VIDEO:
                    case SEND_VOICE_NOTE:
                    case SEND_FILE:
                        response = handleInitiateMediaSend(request.getPayload());
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
                    case UPDATE_CHAT_PARTICIPANT_ROLE: // Client sends this, server needs to map
                        response = handleUpdateChatParticipantRole(request.getPayload());
                        break;
                    case REMOVE_CHAT_PARTICIPANT:
                        response = handleRemoveChatParticipant(request.getPayload());
                        break;

                    // Contact management is not yet in client's displayCommands, but handlers can exist
                    case ADD_CONTACT:
                        response = handleAddContact(request.getPayload());
                        break;
                    case GET_CONTACTS:
                        response = handleGetContacts();
                        break;
                    case REMOVE_CONTACT:
                        response = handleRemoveContact(request.getPayload());
                        break;
                    case BLOCK_UNBLOCK_USER: // Client uses this single command
                        response = handleBlockUnblockUser(request.getPayload());
                        break;

                    case MY_NOTIFICATIONS: // Client sends this
                        response = handleGetUserNotifications();
                        break;
                    case MARK_NOTIFICATION_AS_READ:
                        response = handleMarkNotificationAsRead(request.getPayload());
                        break;
                    case DELETE_NOTIFICATION:
                        response = handleDeleteNotification(request.getPayload());
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

            System.out.println("payload :"+payload);
            Type type = new TypeToken<Map<String, String>>() {}.getType();
            Map<String, String> loginData = gson.fromJson(payload, type);

            String phoneNumber = loginData.get("phone_number");
            String password = loginData.get("password");

            Optional<User> userOptional = userDao.getUserByPhoneNumber(phoneNumber);
            if (userOptional.isPresent()) {
                User user = userOptional.get();
                // In a real app, compare hashed passwords: PasswordHasher.verify(password, user.getPassword())
                if (user.getPassword().equals(password)) { // Simplified for demo
                    currentUserId = user.getId();
                    loggedInUsers.put(currentUserId, this);
                    userDao.updateUserOnlineStatus(currentUserId, true); // Set online
                    //user.setPassword(null); // Clear password before sending
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
                userDao.updateUserOnlineStatus(currentUserId, false); // Mark offline
                this.currentUserId = -1; // Reset for this handler
                return new Response(true, "Logged out successfully.", null);
            }
            return new Response(false, "No user was logged in for this session.", null);
        }

        private Response handleSendTextMessage(String payload) {
            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> messageData = gson.fromJson(payload, type);

            int chatId = ((Double) messageData.get("chat_id")).intValue();
            String content = (String) messageData.get("content");

            // Basic validation
            if (content == null || content.trim().isEmpty()) {
                return new Response(false, "Message content cannot be empty.", null);
            }

            try {
                // Ensure user is a participant of the chat
                if (!chatParticipantDao.isUserParticipant(chatId, currentUserId)) {
                    return new Response(false, "You are not a participant of this chat.", null);
                }

                Message message = new Message();
                message.setChatId(chatId);
                message.setSenderId(currentUserId);
                message.setContent(content);
                message.setSentAt(LocalDateTime.now());
                message.setMessageType("text"); // Explicitly set text type
                message.setViewCount(0); // Initialize view count

                int messageId = messageDao.createMessage(message);

                if (messageId != -1) {
                    message.setId(messageId);
                    // Notify other participants in the chat
                    notifyChatParticipants(chatId, new Response(true, "New message received", gson.toJson(message)));
                    return new Response(true, "Message sent successfully!", gson.toJson(message));
                } else {
                    return new Response(false, "Failed to send message.", null);
                }
            } catch (SQLException e) {
                System.err.println("Error sending message: " + e.getMessage());
                return new Response(false, "Server error sending message.", null);
            }
        }


        private Response handleInitiateMediaSend(String payload) {
            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> mediaData = gson.fromJson(payload, type);

            int chatId = ((Double) mediaData.get("chat_id")).intValue();
            String fileName = (String) mediaData.get("file_name");
            long fileSize = ((Double) mediaData.get("file_size")).longValue();
            String mediaType = (String) mediaData.get("message_type");
            String caption = (String) mediaData.get("content");

            if (fileName == null || fileName.isEmpty() || fileSize <= 0) {
                return new Response(false, "Missing file details (name, size) for media transfer.", null);
            }

            try {
                if (!chatParticipantDao.isUserParticipant(chatId, currentUserId)) {
                    return new Response(false, "You are not a participant of this chat.", null);
                }

                // Generate a unique ID for this transfer
                String transferId = UUID.randomUUID().toString();

                // Store the metadata in the server's pending transfers map
                FileTransferMetadata metadata = new FileTransferMetadata(
                        currentUserId, chatId, fileName, fileSize, mediaType, caption
                );
                System.out.println("^^^^^the  encrption is : "+transferId);
                pendingFileTransfers.put(transferId, metadata);

                System.out.println("Server: Initiating media send for '" + fileName + "' (transferId: " + transferId + ")");
                // Send a special response to the client with the transferId
                // The client will use this ID when connecting to the file transfer port
                return new Response(true, "READY_TO_RECEIVE_FILE", gson.toJson(Map.of("transfer_id", transferId)));

            } catch (SQLException e) {
                System.err.println("Error initiating media send: " + e.getMessage());
                return new Response(false, "Server error initiating media send.", null);
            }
        }
//
//        private Response handleInitiateMediaSend(String payload) {
//            System.out.println("every thing is good 1");
//            Type type = new TypeToken<Map<String, Object>>() {}.getType();
//            Map<String, Object> mediaData = gson.fromJson(payload, type);
//
//            int chatId = ((Double) mediaData.get("chat_id")).intValue();
//            String fileName = (String) mediaData.get("file_name");
//            long fileSize = ((Double) mediaData.get("file_size")).longValue();
//            String mediaType = (String) mediaData.get("message_type");
//            String caption = (String) mediaData.get("content"); // Caption from client is 'content'
//            System.out.println("every thing is good 2");
//            if (fileName == null || fileName.isEmpty() || fileSize <= 0) {
//                return new Response(false, "Missing file details (name, size) for media transfer.", null);
//            }
//
//            try {
//                System.out.println("every thing is good 3");
//                // Pre-check: Ensure user is a participant of the chat
//                if (!chatParticipantDao.isUserParticipant(chatId, currentUserId)) {
//                    return new Response(false, "You are not a participant of this chat.", null);
//                }
//
//                // Store details temporarily for the FileTransferHandler
//                this.pendingFileTransferChatId = chatId;
//                this.pendingFileTransferFileName = fileName;
//                this.pendingFileTransferFileSize = fileSize;
//                this.pendingFileTransferMediaType = mediaType;
//                this.pendingFileTransferCaption = caption;
//
//                // Send a special response to the client indicating readiness for file transfer
//                return new Response(true, "READY_TO_RECEIVE_FILE", null);
//
//            } catch (SQLException e) {
//                System.err.println("Error initiating media send: " + e.getMessage());
//                return new Response(false, "Server error initiating media send.", null);
//            }
//        }

        // --- Helper for broadcasting messages (simplistic example) ---
        private void notifyChatParticipants(int chatId, Response notificationResponse) {
            try {
                List<ChatParticipant> participants = chatParticipantDao.getChatParticipants(chatId);
                for (ChatParticipant participant : participants) {
                    ClientHandler handler = loggedInUsers.get(participant.getUserId());
                    if (handler != null && handler.currentUserId != currentUserId) { // Don't send to self (sender)
                        handler.out.println(notificationResponse.toJson());
                    }
                }
            } catch (Exception e) {
                System.err.println("Error notifying chat participants: " + e.getMessage());
            }
        }

        // --- Existing and New Command Implementations (User Management) ---

        private Response handleRegister(String payload) {
            System.out.println(payload);
            User newUser = gson.fromJson(payload, User.class);
            System.out.println(newUser.getFirstName());

                if (newUser.getPhoneNumber() == null || newUser.getPhoneNumber().isEmpty() ||
                    newUser.getPassword() == null || newUser.getPassword().isEmpty() ||
                    newUser.getFirstName() == null || newUser.getFirstName().isEmpty()) { // Added firstName as required
                return new Response(false, "Missing required registration fields.", null);
            }

            if (userDao.getUserByPhoneNumber(newUser.getPhoneNumber()).isPresent()) {
                return new Response(false, "Phone number already registered.", null);
            }
            // Optional: Check username if you implement it
            // if (newUser.getUsername() != null && userDao.getUserByUsername(newUser.getUsername()).isPresent()) {
            //    return new Response(false, "Username already taken.", null);
            // }

            int userId = userDao.createUser(newUser);
            if (userId != -1) {
                newUser.setId(userId);
                newUser.setPassword(null); // Clear password before sending
                return new Response(true, "Registration successful!", gson.toJson(newUser));
            } else {
                return new Response(false, "Failed to register user.", null);
            }
        }

        private Response handleGetUserProfile(String payload) {
            Type type = new TypeToken<Map<String, Double>>() {}.getType(); // Use Double for numbers from JSON
            Map<String, Double> params = gson.fromJson(payload, type);
            int targetUserId = params.get("userId").intValue(); // Convert to int

            Optional<User> userOptional = userDao.getUserById(targetUserId);
            if (userOptional.isPresent()) {
                User user = userOptional.get();
                user.setPassword(null); // IMPORTANT: Never send password hash to client
                return new Response(true, "User profile retrieved.", gson.toJson(user));
            } else {
                return new Response(false, "User not found.", null);
            }
        }

        private Response handleUpdateUserProfile(String payload) {
            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> updates = gson.fromJson(payload, type);
            int userIdToUpdate = ((Double) updates.get("userId")).intValue(); // Get user ID from payload

            if (userIdToUpdate != currentUserId) {
                return new Response(false, "Unauthorized: You can only update your own profile.", null);
            }

            User existingUser = userDao.getUserById(currentUserId).orElse(null);
            if (existingUser == null) {
                return new Response(false, "User not found for update.", null);
            }

            // Apply updates only for fields that are present in the payload and allowed to be updated
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
                // Handle null if client sends empty string to clear URL
                String url = (String) updates.get("profile_picture_url");
                existingUser.setProfilePictureUrl(url != null && url.isEmpty() ? null : url);
            }

            boolean success = userDao.updateUser(existingUser);
            if (success) {
                existingUser.setPassword(null); // Clear password
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
            newChat.setChatName(chatName); // Can be null for private chats
            newChat.setChatDescription(chatDescription);
            newChat.setPublicLink(publicLink);
            newChat.setCreatorId(currentUserId); // Current user is the creator
            newChat.setCreatedAt(LocalDateTime.now());

            int chatId = chatDao.createChat(newChat);
            if (chatId != -1) {
                newChat.setId(chatId);
                // Automatically add the creator as a participant with 'creator' role
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



        private Response handleGetUserChats() {
            try {
                // Get all chats the current user is a participant of
                // ChatDao chatDao = new ChatDao(); // Already defined as a member of ChatServer, accessible via ChatServer.this.chatDao
                List<Chat> chats = chatDao.getUserChats(currentUserId);
                return new Response(true, "User chats retrieved.", gson.toJson(chats));
            } catch (SQLException e) {
                System.err.println("Error getting user chats: " + e.getMessage());
                return new Response(false, "Server error retrieving user chats.", null);
            }
        }

        private Response handleGetChatDetails(String payload) {
            try {
                // The payload for this command is typically just the chat ID
                Type type = new TypeToken<Map<String, Double>>() {}.getType();
                Map<String, Double> params = gson.fromJson(payload, type);
                int chatId = params.get("chatId").intValue();

                // ChatDao chatDao = new ChatDao(); // Already defined
                Optional<Chat> chatOptional = chatDao.getChatById(chatId);

                // IMPORTANT: Check if the user is a participant of this chat
                // ChatParticipantDao cpDao = new ChatParticipantDao(); // Already defined
                if (!chatParticipantDao.isUserParticipant(chatId, currentUserId)) {
                    return new Response(false, "Unauthorized: You are not a participant of this chat.", null);
                }

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
            try {
                // The payload contains the Chat object with updated fields
                Chat updatedChat = gson.fromJson(payload, Chat.class);

                // ChatDao chatDao = new ChatDao(); // Already defined
                Optional<Chat> existingChatOptional = chatDao.getChatById(updatedChat.getId());

                if (!existingChatOptional.isPresent()) {
                    return new Response(false, "Chat not found.", null);
                }
                Chat existingChat = existingChatOptional.get();

                // Authorization: Only the chat creator or an admin can update chat details
                // ChatParticipantDao cpDao = new ChatParticipantDao(); // Already defined
                Optional<ChatParticipant> currentUserParticipant = chatParticipantDao.getChatParticipantById(existingChat.getId());

                if (currentUserParticipant.isEmpty() ||
                        (!"creator".equalsIgnoreCase(currentUserParticipant.get().getRole()) &&
                                !"admin".equalsIgnoreCase(currentUserParticipant.get().getRole()))) {
                    return new Response(false, "Unauthorized: Only chat creators or admins can update chat details.", null);
                }

                // Apply updates for allowed fields (e.g., chatName, chatDescription, publicLink)
                // Do NOT allow changing ID, Creator ID, or CreatedAt from client
                if (updatedChat.getChatName() != null) {
                    existingChat.setChatName(updatedChat.getChatName());
                }
                if (updatedChat.getChatDescription() != null) {
                    existingChat.setChatDescription(updatedChat.getChatDescription());
                }
                if (updatedChat.getPublicLink() != null) {
                    existingChat.setPublicLink(updatedChat.getPublicLink());
                }
                // Be careful allowing chatType change, might have complex implications
                if (updatedChat.getChatType() != null) {
                    existingChat.setChatType(updatedChat.getChatType());
                }


                boolean success = chatDao.updateChat(existingChat);
                if (success) {
                    return new Response(true, "Chat updated successfully!", gson.toJson(existingChat));
                } else {
                    return new Response(false, "Failed to update chat.", null);
                }
            } catch (SQLException e) {
                System.err.println("Error updating chat: " + e.getMessage());
                return new Response(false, "Server error updating chat.", null);
            }
        }

        private Response handleDeleteChat(String payload) {
            // The payload contains the chat ID to delete
            Type type = new TypeToken<Map<String, Double>>() {}.getType();
            Map<String, Double> params = gson.fromJson(payload, type);
            int chatId = params.get("chatId").intValue();

            // ChatDao chatDao = new ChatDao(); // Already defined
            Optional<Chat> chatOptional = chatDao.getChatById(chatId);

            if (!chatOptional.isPresent()) {
                return new Response(false, "Chat not found.", null);
            }
            Chat chatToDelete = chatOptional.get();

            // Authorization: Only the chat creator can delete the chat
            if (chatToDelete.getCreatorId() != currentUserId) {
                return new Response(false, "Unauthorized: Only the chat creator can delete this chat.", null);
            }

            // Deleting a chat should ideally also delete related messages and chat participants.
            // This would be handled by CASCADE DELETE in the database schema or explicit DAO calls.
            boolean success = chatDao.deleteChat(chatId);
            if (success) {
                // Optionally notify all participants that the chat has been deleted
                // In a real system, you'd fetch all participants, and send them a "chat_deleted" notification.
                return new Response(true, "Chat deleted successfully.", null);
            } else {
                return new Response(false, "Failed to delete chat.", null);
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
            try {
                // The payload for this command is typically just the chat ID
                Type type = new TypeToken<Map<String, Double>>() {}.getType();
                Map<String, Double> params = gson.fromJson(payload, type);
                int chatId = params.get("chat_id").intValue();

                // ChatParticipantDao cpDao = new ChatParticipantDao(); // Already defined

                // Authorization: User must be a participant to view participants
                if (!chatParticipantDao.isUserParticipant(chatId, currentUserId)) {
                    return new Response(false, "Unauthorized: You are not a participant of this chat.", null);
                }

                List<ChatParticipant> participants = chatParticipantDao.getChatParticipants(chatId);
                return new Response(true, "Chat participants retrieved.", gson.toJson(participants));
            } catch (SQLException e) {
                System.err.println("Error getting chat participants: " + e.getMessage());
                return new Response(false, "Server error retrieving chat participants.", null);
            }
        }


        private Response handleGetChatMessages(String payload) {
            Type type = new TypeToken<Map<String, Double>>() {}.getType();
            Map<String, Double> params = gson.fromJson(payload, type);
            int chatId = params.get("chat_id").intValue();
            int limit = params.get("limit").intValue();
            int offset = params.get("offset").intValue();

            try {
                // Ensure user is a participant of the chat
                if (!chatParticipantDao.isUserParticipant(chatId, currentUserId)) {
                    return new Response(false, "Unauthorized: You are not a participant of this chat.", null);
                }

                List<Message> messages = messageDao.getChatMessages(chatId, limit, offset);
                System.out.println(" -------------- this is the massges count "+ messages.size());
                System.out.println(messages.toString());
                return new Response(true, "Messages retrieved", gson.toJson(messages));

            } catch (SQLException e) {
                System.err.println("Error getting chat messages: " + e.getMessage());
                return new Response(false, "Server error retrieving messages.", null);
            }
        }

        // --- Message Management ---
        private Response handleUpdateMessage(String payload) {
            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> updateData = gson.fromJson(payload, type);
            int messageId = ((Double) updateData.get("message_id")).intValue();
            String newContent = (String) updateData.get("new_content");

            try {
                Optional<Message> existingMessageOptional = messageDao.getMessageById(messageId);
                if (!existingMessageOptional.isPresent()) {
                    return new Response(false, "Message not found.", null);
                }
                Message existingMessage = existingMessageOptional.get();

                if (existingMessage.getSenderId() != currentUserId) {
                    return new Response(false, "Unauthorized: You can only update your own messages.", null);
                }

                existingMessage.setContent(newContent);
                existingMessage.setEditedAt(LocalDateTime.now());

                boolean success = messageDao.updateMessage(existingMessage);
                if (success) {
                    notifyChatParticipants(existingMessage.getChatId(), new Response(true, "New message received", gson.toJson(existingMessage))); // Notify of update
                    return new Response(true, "Message updated successfully!", gson.toJson(existingMessage));
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
                Optional<Message> messageOptional = messageDao.getMessageById(messageId);
                if (!messageOptional.isPresent()) {
                    return new Response(false, "Message not found.", null);
                }
                Message messageToDelete = messageOptional.get();

                // Authorization: Only sender can delete their message, or chat admin/creator
                if (messageToDelete.getSenderId() != currentUserId) {
                    // Check if current user is an admin or creator of the chat
                    Optional<ChatParticipant> userParticipant = chatParticipantDao.getChatParticipantById(messageToDelete.getChatId());
                    if (userParticipant.isEmpty() || (!"creator".equals(userParticipant.get().getRole()) && !"admin".equals(userParticipant.get().getRole()))) {
                        return new Response(false, "Unauthorized: You can only delete your own messages or be a chat admin.", null);
                    }
                }

                boolean success = messageDao.deleteMessage(messageId);
                if (success) {
                    // Send a notification that message was deleted
                    Response deleteNotification = new Response(true, "Message deleted", gson.toJson(Map.of("message_id", messageId, "chat_id", messageToDelete.getChatId())));
                    notifyChatParticipants(messageToDelete.getChatId(), deleteNotification);
                    return new Response(true, "Message deleted successfully.", null);
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
                Optional<Message> messageOptional = messageDao.getMessageById(messageId);
                if (!messageOptional.isPresent()) {
                    return new Response(false, "Message not found.", null);
                }
                Message message = messageOptional.get();

                // Check if the current user is a participant of the chat where the message is
                if (!chatParticipantDao.isUserParticipant(message.getChatId(), currentUserId)) {
                    return new Response(false, "Unauthorized: You are not a participant of this chat.", null);
                }

                // Increment view count (simplified read receipt)
                message.setViewCount(message.getViewCount() + 1);
                boolean success = messageDao.updateMessage(message); // Re-using updateMessage, or specific updateViewCount in DAO
                if (success) {
                    // Optionally, notify sender about read receipt
                    return new Response(true, "Message marked as read.", null);
                } else {
                    return new Response(false, "Failed to mark message as read.", null);
                }
            } catch (SQLException e) {
                System.err.println("Error marking message as read: " + e.getMessage());
                return new Response(false, "Server error marking message as read.", null);
            }
        }

        // --- Chat Participant Management ---

        private Response handleUpdateChatParticipantRole(String payload) {
            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> updateData = gson.fromJson(payload, type);
            int chatId = ((Double) updateData.get("chat_id")).intValue();
            int userId = ((Double) updateData.get("user_id")).intValue();
            String newRole = (String) updateData.get("new_role");

            try {
                // Authorization: Only chat creator/admin can update roles
                Optional<ChatParticipant> currentUserAsParticipant = chatParticipantDao.getChatParticipantById(chatId);
                if (currentUserAsParticipant.isEmpty() || (!"creator".equals(currentUserAsParticipant.get().getRole()) && !"admin".equals(currentUserAsParticipant.get().getRole()))) {
                    return new Response(false, "Unauthorized: Only chat creators or admins can update participant roles.", null);
                }

                // Cannot change the role of the chat creator (if you enforce this)
                Chat chat = chatDao.getChatById(chatId).orElse(null);
                if (chat != null && chat.getCreatorId() == userId && !newRole.equals("creator")) {
                    return new Response(false, "Cannot change the role of the chat creator.", null);
                }

                boolean success = chatParticipantDao.updateParticipantRole(chatId, userId, newRole);
                if (success) {
                    return new Response(true, "Participant role updated successfully!", null);
                } else {
                    return new Response(false, "Failed to update participant role (participant not found or invalid role).", null);
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
                // Authorization: Only chat creator/admin can remove participants, or a user can leave themselves
                Optional<ChatParticipant> currentUserAsParticipant = chatParticipantDao.getChatParticipantById(chatId);

                if (currentUserAsParticipant.isEmpty()) {
                    return new Response(false, "Unauthorized: You are not a participant of this chat.", null);
                }

                boolean isCreatorOrAdmin = "creator".equals(currentUserAsParticipant.get().getRole()) || "admin".equals(currentUserAsParticipant.get().getRole());

                if (userIdToRemove != currentUserId && !isCreatorOrAdmin) {
                    return new Response(false, "Unauthorized: You can only remove yourself or be a chat admin/creator to remove others.", null);
                }

                boolean success = chatParticipantDao.deleteChatParticipant(chatId, userIdToRemove);
                if (success) {
                    return new Response(true, "Participant removed successfully.", null);
                } else {
                    return new Response(false, "Failed to remove participant (participant not found).", null);
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
            int targetUserId = params.get("target_user_id").intValue();

            try {
                // Check if target user exists
                if (!userDao.getUserById(targetUserId).isPresent()) {
                    return new Response(false, "Target user not found.", null);
                }
                // Cannot add self as contact
                if (targetUserId == currentUserId) {
                    return new Response(false, "Cannot add yourself as a contact.", null);
                }



                int success = contactDao.createContact(new Contact( currentUserId,targetUserId));
                if (success != -1) {
                    return new Response(true, "Contact added successfully!", null);
                } else {
                    return new Response(false, "Failed to add contact (already exists or database error).", null);
                }
            } catch (SQLException e) {
                System.err.println("Error adding contact: " + e.getMessage());
                return new Response(false, "Server error adding contact.", null);
            }
        }

        private Response handleGetContacts() {
            try {
                List<User> contacts = contactDao.getUserContacts(currentUserId);
                contacts.forEach(u -> u.setPassword(null)); // Strip sensitive data
                return new Response(true, "Contacts retrieved.", gson.toJson(contacts));
            } catch (SQLException e) {
                System.err.println("Error getting contacts: " + e.getMessage());
                return new Response(false, "Server error retrieving contacts.", null);
            }
        }

        private Response handleRemoveContact(String payload) {
            Type type = new TypeToken<Map<String, Double>>() {}.getType();
            Map<String, Double> params = gson.fromJson(payload, type);
            int targetUserId = params.get("target_user_id").intValue();

            boolean success = contactDao.deleteContact(currentUserId, targetUserId);
            if (success) {
                return new Response(true, "Contact removed successfully.", null);
            } else {
                return new Response(false, "Failed to remove contact (not found).", null);
            }
        }

        private Response handleBlockUnblockUser(String payload) {
            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> params = gson.fromJson(payload, type);
            int targetUserId = ((Double) params.get("target_user_id")).intValue();
            String action = (String) params.get("action"); // "block" or "unblock"

            if (targetUserId == currentUserId) {
                return new Response(false, "Cannot block/unblock yourself.", null);
            }

            boolean success = true;
            if ("block".equalsIgnoreCase(action)) {
                //success = contactDao.blockUser(currentUserId, targetUserId);
                if (success) {
                    return new Response(true, "User " + targetUserId + " blocked.", null);
                } else {
                    return new Response(false, "Failed to block user " + targetUserId + " (already blocked or error).", null);
                }
            } else if ("unblock".equalsIgnoreCase(action)) {
                //success = contactDao.unblockUser(currentUserId, targetUserId);
                if (success) {
                    return new Response(true, "User " + targetUserId + " unblocked.", null);
                } else {
                    return new Response(false, "Failed to unblock user " + targetUserId + " (not blocked or error).", null);
                }
            } else {
                return new Response(false, "Invalid action for block/unblock. Use 'block' or 'unblock'.", null);
            }
        }

        // --- Notification Management ---

        private Response handleGetUserNotifications() {
            try {
                List<Notification> notifications = notificationDao.getNotificationsByUserId(currentUserId);
                return new Response(true, "Notifications retrieved.", gson.toJson(notifications));
            } catch (SQLException e) {
                System.err.println("Error getting notifications: " + e.getMessage());
                return new Response(false, "Server error retrieving notifications.", null);
            }
        }

        private Response handleMarkNotificationAsRead(String payload) {
            Type type = new TypeToken<Map<String, Double>>() {}.getType();
            Map<String, Double> params = gson.fromJson(payload, type);
            int notificationId = params.get("notificationId").intValue();

            try {
                // Verify ownership (optional, but good practice)
                Optional<Notification> notificationOptional = notificationDao.getNotificationById(notificationId);
                if (notificationOptional.isEmpty() || notificationOptional.get().getRecipientUserId()!= currentUserId) {
                    return new Response(false, "Unauthorized or notification not found.", null);
                }

                boolean success = notificationDao.markNotificationAsRead(notificationId);
                if (success) {
                    return new Response(true, "Notification marked as read.", null);
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
            int notificationId = params.get("notificationId").intValue();

            try {
                // Verify ownership
                Optional<Notification> notificationOptional = notificationDao.getNotificationById(notificationId);
                if (notificationOptional.isEmpty() || notificationOptional.get().getRecipientUserId() != currentUserId) {
                    return new Response(false, "Unauthorized or notification not found.", null);
                }

                boolean success = notificationDao.deleteNotification(notificationId);
                if (success) {
                    return new Response(true, "Notification deleted.", null);
                } else {
                    return new Response(false, "Failed to delete notification.", null);
                }
            } catch (SQLException e) {
                System.err.println("Error deleting notification: " + e.getMessage());
                return new Response(false, "Server error deleting notification.", null);
            }
        }
    }

    public static void main(String[] args) {
        ChatServer2 server = new ChatServer2();
        server.start();
    }
}