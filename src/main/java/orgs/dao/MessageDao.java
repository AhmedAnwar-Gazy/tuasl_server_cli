package orgs.dao;

import orgs.model.Message;
import orgs.utils.DatabaseConnection;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


public class MessageDao {

    // Create (Send a Message)
    public int createMessage(Message message) {
        String sql = "INSERT INTO messages (chat_id, sender_id, content, message_type, media_id, replied_to_message_id, forwarded_from_user_id, forwarded_from_chat_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        int generatedId = -1;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            pstmt.setInt(1, message.getChatId());
            pstmt.setInt(2, message.getSenderId());
            pstmt.setString(3, message.getContent());
            pstmt.setString(4, message.getMessageType());
            pstmt.setObject(5, message.getMediaId(), Types.INTEGER); // Handle nullable Integer
            pstmt.setObject(6, message.getRepliedToMessageId(), Types.INTEGER); // Handle nullable Integer
            pstmt.setObject(7, message.getForwardedFromUserId(), Types.INTEGER); // Handle nullable Integer
            pstmt.setObject(8, message.getForwardedFromChatId(), Types.INTEGER); // Handle nullable Integer

            int affectedRows = pstmt.executeUpdate();
            if (affectedRows > 0) {
                try (ResultSet rs = pstmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        generatedId = rs.getInt(1);
                        message.setId(generatedId);
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Error creating message: " + e.getMessage());
            e.printStackTrace();
        }
        return generatedId;
    }

    // Read (Retrieve Messages)
    public List<Message> getMessagesByChatId(int chatId, int limit) {
        List<Message> messages = new ArrayList<>();
        // Note: ORDER BY sent_at DESC for most recent first as suggested in SQL, adjusted to ASC for chronological if getting older messages
        // Changed to ASC for typical chat history loading (older to newer)
        String sql = "SELECT m.*, u.username AS sender_username, " +
                "r_m.content AS replied_to_content, r_u.username AS replied_to_sender_username " +
                "FROM messages m " +
                "JOIN users u ON m.sender_id = u.id " +
                "LEFT JOIN messages r_m ON m.replied_to_message_id = r_m.id " +
                "LEFT JOIN users r_u ON r_m.sender_id = r_u.id " +
                "WHERE m.chat_id = ? AND m.is_deleted = FALSE " +
                "ORDER BY m.sent_at  " + // Most recent first for initial load
                "LIMIT ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, chatId);
            pstmt.setInt(2, limit);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    messages.add(mapResultSetToMessage(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting messages by chat ID: " + e.getMessage());
            e.printStackTrace();
        }
        return messages;
    }

    public List<Message> getMessagesAfterId(int chatId, int lastMessageId, int limit) {
        List<Message> messages = new ArrayList<>();
        String sql = "SELECT m.* FROM messages m WHERE m.chat_id = ? AND m.id > ? AND m.is_deleted = FALSE ORDER BY m.sent_at ASC LIMIT ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, chatId);
            pstmt.setInt(2, lastMessageId);
            pstmt.setInt(3, limit);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    messages.add(mapResultSetToMessage(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting messages after ID: " + e.getMessage());
            e.printStackTrace();
        }
        return messages;
    }

    public List<Message> getUnreadMessagesForUserInChat(int userId, int chatId) {
        List<Message> messages = new ArrayList<>();
        String sql = "SELECT m.* " +
                "FROM messages m " +
                "JOIN chat_participants cp ON m.chat_id = cp.chat_id " +
                "WHERE cp.user_id = ? " +
                "  AND m.chat_id = ? " +
                "  AND (m.id > cp.last_read_message_id OR cp.last_read_message_id IS NULL) " +
                "  AND m.is_deleted = FALSE " +
                "ORDER BY m.sent_at ASC";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, userId);
            pstmt.setInt(2, chatId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    messages.add(mapResultSetToMessage(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting unread messages: " + e.getMessage());
            e.printStackTrace();
        }
        return messages;
    }

    public List<Message> getAllUndeletedMessages() {
        List<Message> messages = new ArrayList<>();
        String sql = "SELECT m.* FROM messages m WHERE m.is_deleted = FALSE";
        try (Connection conn = DatabaseConnection.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                messages.add(mapResultSetToMessage(rs));
            }
        } catch (SQLException e) {
            System.err.println("Error getting all undeleted messages: " + e.getMessage());
            e.printStackTrace();
        }
        return messages;
    }


    // Update (Modify Message Information)
    public boolean editMessage(int messageId, String newContent) {
        String sql = "UPDATE messages SET content = ?, edited_at = ? WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, newContent);
            pstmt.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            pstmt.setInt(3, messageId);
            int affectedRows = pstmt.executeUpdate();
            return affectedRows > 0;
        } catch (SQLException e) {
            System.err.println("Error editing message: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public boolean softDeleteMessage(int messageId) {
        String sql = "UPDATE messages SET is_deleted = TRUE WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, messageId);
            int affectedRows = pstmt.executeUpdate();
            return affectedRows > 0;
        } catch (SQLException e) {
            System.err.println("Error soft deleting message: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public boolean incrementViewCount(int messageId) {
        String sql = "UPDATE messages SET view_count = view_count + 1 WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, messageId);
            int affectedRows = pstmt.executeUpdate();
            return affectedRows > 0;
        } catch (SQLException e) {
            System.err.println("Error incrementing view count: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // Delete (Remove a Message) - Hard delete for soft-deleted messages
    public boolean hardDeleteSoftDeletedMessage(int messageId) {
        String sql = "DELETE FROM messages WHERE id = ? AND is_deleted = TRUE";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, messageId);
            int affectedRows = pstmt.executeUpdate();
            return affectedRows > 0;
        } catch (SQLException e) {
            System.err.println("Error hard deleting soft-deleted message: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // Helper method to map ResultSet to Message object
    private Message mapResultSetToMessage(ResultSet rs) throws SQLException {
        Message message = new Message();
        message.setId(rs.getInt("id"));
        message.setChatId(rs.getInt("chat_id"));
        message.setSenderId(rs.getInt("sender_id"));
        message.setContent(rs.getString("content"));
        message.setMessageType(rs.getString("message_type"));
        message.setSentAt(rs.getTimestamp("sent_at").toLocalDateTime());

        // Handle nullable fields
        int mediaId = rs.getInt("media_id");
        if (rs.wasNull()) message.setMediaId(null);
        else message.setMediaId(mediaId);

        int repliedToMessageId = rs.getInt("replied_to_message_id");
        if (rs.wasNull()) message.setRepliedToMessageId(null);
        else message.setRepliedToMessageId(repliedToMessageId);

        int forwardedFromUserId = rs.getInt("forwarded_from_user_id");
        if (rs.wasNull()) message.setForwardedFromUserId(null);
        else message.setForwardedFromUserId(forwardedFromUserId);

        int forwardedFromChatId = rs.getInt("forwarded_from_chat_id");
        if (rs.wasNull()) message.setForwardedFromChatId(null);
        else message.setForwardedFromChatId(forwardedFromChatId);

        Timestamp editedAtTimestamp = rs.getTimestamp("edited_at");
        message.setEditedAt(editedAtTimestamp != null ? editedAtTimestamp.toLocalDateTime() : null);

        message.setDeleted(rs.getBoolean("is_deleted"));
        message.setViewCount(rs.getInt("view_count"));
        return message;
    }


    //-----------------------

//    public int createMessage(Message message) throws SQLException {
//        String sql = "INSERT INTO messages (chat_id, sender_id, content, message_type, media_id, replied_to_message_id, forwarded_from_user_id, forwarded_from_chat_id, view_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
//        try (Connection conn = DatabaseConnection.getConnection();
//             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
//            stmt.setInt(1, message.getChatId());
//            stmt.setInt(2, message.getSenderId());
//            stmt.setString(3, message.getContent());
//            stmt.setString(4, message.getMessageType());
//            if (message.getMediaId() != null) stmt.setInt(5, message.getMediaId()); else stmt.setNull(5, java.sql.Types.INTEGER);
//            if (message.getRepliedToMessageId() != null) stmt.setInt(6, message.getRepliedToMessageId()); else stmt.setNull(6, java.sql.Types.INTEGER);
//            if (message.getForwardedFromUserId() != null) stmt.setInt(7, message.getForwardedFromUserId()); else stmt.setNull(7, java.sql.Types.INTEGER);
//            if (message.getForwardedFromChatId() != null) stmt.setInt(8, message.getForwardedFromChatId()); else stmt.setNull(8, java.sql.Types.INTEGER);
//            stmt.setInt(9, message.getViewCount()); // Default is 0
//
//            int affectedRows = stmt.executeUpdate();
//
//            if (affectedRows == 0) {
//                throw new SQLException("Creating message failed, no rows affected.");
//            }
//
//            try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
//                if (generatedKeys.next()) {
//                    return generatedKeys.getInt(1);
//                } else {
//                    throw new SQLException("Creating message failed, no ID obtained.");
//                }
//            }
//        }
//    }

    // Assuming you have this already
    public List<Message> getChatMessages(int chatId, int limit, int offset) throws SQLException {
        List<Message> messages = new ArrayList<>();
        String sql = "SELECT id, chat_id, sender_id, content, message_type, sent_at, media_id, replied_to_message_id, forwarded_from_user_id, forwarded_from_chat_id, edited_at, is_deleted, view_count FROM messages WHERE chat_id = ? ORDER BY sent_at DESC LIMIT ? OFFSET ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, chatId);
            stmt.setInt(2, limit);
            stmt.setInt(3, offset);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    messages.add(mapResultSetToMessage(rs));
                }
            }
        }
        return messages;
    }

    // New method: getMessageById(messageId)
    public Optional<Message> getMessageById(int id) throws SQLException {
        String sql = "SELECT id, chat_id, sender_id, content, message_type, sent_at, media_id, replied_to_message_id, forwarded_from_user_id, forwarded_from_chat_id, edited_at, is_deleted, view_count FROM messages WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToMessage(rs));
                }
            }
        }
        return Optional.empty();
    }

    // New method: updateMessage(existingMessage)
    public boolean updateMessage(Message message) throws SQLException {
        String sql = "UPDATE messages SET content = ?, edited_at = ?, is_deleted = ?, view_count = ? WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, message.getContent());
            stmt.setTimestamp(2, message.getEditedAt() != null ? Timestamp.valueOf(message.getEditedAt()) : null);
            stmt.setBoolean(3, message.isDeleted());
            stmt.setInt(4, message.getViewCount());
            stmt.setInt(5, message.getId());
            return stmt.executeUpdate() > 0;
        }
    }

    // New method: deleteMessage(messageId) - performs soft delete
    public boolean deleteMessage(int id) throws SQLException {
        String sql = "UPDATE messages SET is_deleted = TRUE, content = 'This message was deleted.', edited_at = CURRENT_TIMESTAMP WHERE id = ?";
        // Or for hard delete: "DELETE FROM messages WHERE id = ?"
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, id);
            return stmt.executeUpdate() > 0;
        }
    }

//    private Message mapResultSetToMessage(ResultSet rs) throws SQLException {
//        Message message = new Message();
//        message.setId(rs.getInt("id"));
//        message.setChatId(rs.getInt("chat_id"));
//        message.setSenderId(rs.getInt("sender_id"));
//        message.setContent(rs.getString("content"));
//        message.setMessageType(rs.getString("message_type"));
//        message.setSentAt(rs.getTimestamp("sent_at").toLocalDateTime());
//
//        int mediaId = rs.getInt("media_id");
//        if (rs.wasNull()) message.setMediaId(null); else message.setMediaId(mediaId);
//
//        int repliedToMessageId = rs.getInt("replied_to_message_id");
//        if (rs.wasNull()) message.setRepliedToMessageId(null); else message.setRepliedToMessageId(repliedToMessageId);
//
//        int forwardedFromUserId = rs.getInt("forwarded_from_user_id");
//        if (rs.wasNull()) message.setForwardedFromUserId(null); else message.setForwardedFromUserId(forwardedFromUserId);
//
//        int forwardedFromChatId = rs.getInt("forwarded_from_chat_id");
//        if (rs.wasNull()) message.setForwardedFromChatId(null); else message.setForwardedFromChatId(forwardedFromChatId);
//
//        Timestamp editedAtTimestamp = rs.getTimestamp("edited_at");
//        message.setEditedAt(editedAtTimestamp != null ? editedAtTimestamp.toLocalDateTime() : null);
//
//        message.setDeleted(rs.getBoolean("is_deleted"));
//        message.setViewCount(rs.getInt("view_count"));
//        return message;
//    }



}
