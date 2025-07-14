// src/orgs/server/FileTransferHandler.java
package orgs.server;

import orgs.dao.MessageDao;
import orgs.model.Message;

import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;

public class FileTransferHandler implements Runnable {
    private Socket fileSocket;
    private int senderId;
    private int chatId;
    private String fileName;
    private long fileSize;
    private String mediaType;
    private String caption;
    private MessageDao messageDao; // To save the message metadata

    public FileTransferHandler(Socket fileSocket, int senderId, int chatId, String fileName, long fileSize, String mediaType, String caption, MessageDao messageDao) {
        this.fileSocket = fileSocket;
        this.senderId = senderId;
        this.chatId = chatId;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.mediaType = mediaType;
        this.caption = caption;
        this.messageDao = messageDao;
    }

    @Override
    public void run() {

        String serverUploadDir =   "src/main/resources/uploads";    // FileStorageManager;
        Path filePath = Paths.get(serverUploadDir, fileName);

        try (InputStream is = fileSocket.getInputStream();
             BufferedInputStream bis = new BufferedInputStream(is);
             FileOutputStream fos = new FileOutputStream(filePath.toFile())) {

            System.out.println("Server: Receiving file '" + fileName + "' from client...");

            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalBytesReceived = 0;

            while (totalBytesReceived < fileSize && (bytesRead = bis.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
                totalBytesReceived += bytesRead;
                // Optional: print progress for large files
                // System.out.print("\rServer: Received " + totalBytesReceived + " of " + fileSize + " bytes");
            }
            fos.flush(); // Ensure all bytes are written to file

            System.out.println("\nServer: File '" + fileName + "' received. Size: " + totalBytesReceived + " bytes.");

            // After receiving the file, save its metadata to the database as a message
            Message mediaMessage = new Message();
            mediaMessage.setChatId(chatId);
            mediaMessage.setSenderId(senderId);
            mediaMessage.setContent(caption); // Caption as message content
            mediaMessage.setMessageType(mediaType);
            //mediaMessage.setMediaId(filePath.getFileName().toString()); // Store filename as media ID
            mediaMessage.setMediaId(5); // Store filename as media ID
            mediaMessage.setSentAt(LocalDateTime.now());
            mediaMessage.setViewCount(0); // Initial view count

            int messageId = messageDao.createMessage(mediaMessage);
            if (messageId != -1) {
                mediaMessage.setId(messageId);
                // Notify relevant clients about the new media message
                // This will be handled by the main command channel's response back to the sender,
                // and potentially by a broadcast mechanism for other chat participants.
                System.out.println("Server: Media message created in DB for file: " + fileName);
            } else {
                System.err.println("Server: Failed to save media message metadata to DB for file: " + fileName);
                // Optionally, delete the partially received file
                Files.deleteIfExists(filePath);
            }

        } catch (IOException e) {
            System.err.println("Server Error during file transfer for '" + fileName + "': " + e.getMessage());
            e.printStackTrace();
            // Clean up: delete incomplete file if an error occurred during transfer
            try {
                Files.deleteIfExists(filePath);
            } catch (IOException ex) {
                System.err.println("Error deleting incomplete file: " + ex.getMessage());
            }
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