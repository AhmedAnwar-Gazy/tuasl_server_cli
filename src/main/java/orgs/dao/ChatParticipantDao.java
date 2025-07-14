package orgs.dao;

import orgs.model.ChatParticipant;
import orgs.utils.DatabaseConnection;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ChatParticipantDao {

    // Create (Add Chat Participants)
    public int createChatParticipant(ChatParticipant participant) {
        String sql = "INSERT INTO chat_participants (chat_id, user_id, role) VALUES (?, ?, ?)";
        int generatedId = -1;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            pstmt.setInt(1, participant.getChatId());
            pstmt.setInt(2, participant.getUserId());
            pstmt.setString(3, participant.getRole());

            int affectedRows = pstmt.executeUpdate();
            if (affectedRows > 0) {
                try (ResultSet rs = pstmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        generatedId = rs.getInt(1);
                        participant.setId(generatedId);
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Error creating chat participant: " + e.getMessage());
            e.printStackTrace();
        }
        return generatedId;
    }

    // Read (Retrieve Chat Participants)
    public List<ChatParticipant> getParticipantsByChatId(int chatId) {
        List<ChatParticipant> participants = new ArrayList<>();
        String sql = "SELECT cp.*, u.username FROM chat_participants cp JOIN users u ON cp.user_id = u.id WHERE cp.chat_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, chatId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    ChatParticipant participant = mapResultSetToChatParticipant(rs);
                    // You might want to extend ChatParticipant model to include User info directly
                    // For now, just getting the username for demonstration
                    System.out.println("Participant: " + rs.getString("username") + ", Role: " + participant.getRole());
                    participants.add(participant);
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting participants by chat ID: " + e.getMessage());
            e.printStackTrace();
        }
        return participants;
    }

    public List<ChatParticipant> getChatsByUserId(int userId) {
        List<ChatParticipant> chats = new ArrayList<>();
        String sql = "SELECT cp.*, c.chat_name, c.chat_type FROM chat_participants cp JOIN chats c ON cp.chat_id = c.id WHERE cp.user_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    ChatParticipant participant = mapResultSetToChatParticipant(rs);
                    // You might want to create a custom DTO or extend ChatParticipant to include chat_name/chat_type
                    System.out.println("Chat: " + rs.getString("chat_name") + ", Type: " + rs.getString("chat_type") + ", Role: " + participant.getRole());
                    chats.add(participant);
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting chats by user ID: " + e.getMessage());
            e.printStackTrace();
        }
        return chats;
    }

    // Update (Modify Participant Information)
    public boolean updateParticipantRole(int chatId, int userId, String newRole) {
        String sql = "UPDATE chat_participants SET role = ? WHERE chat_id = ? AND user_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, newRole);
            pstmt.setInt(2, chatId);
            pstmt.setInt(3, userId);

            int affectedRows = pstmt.executeUpdate();
            return affectedRows > 0;
        } catch (SQLException e) {
            System.err.println("Error updating participant role: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public boolean updateUnreadCount(int chatId, int userId, int newUnreadCount) {
        String sql = "UPDATE chat_participants SET unread_count = ? WHERE chat_id = ? AND user_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, newUnreadCount);
            pstmt.setInt(2, chatId);
            pstmt.setInt(3, userId);

            int affectedRows = pstmt.executeUpdate();
            return affectedRows > 0;
        } catch (SQLException e) {
            System.err.println("Error updating unread count: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public boolean resetUnreadCountAndSetLastReadMessage(int chatId, int userId, int lastReadMessageId) {
        String sql = "UPDATE chat_participants SET unread_count = 0, last_read_message_id = ? WHERE chat_id = ? AND user_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, lastReadMessageId);
            pstmt.setInt(2, chatId);
            pstmt.setInt(3, userId);

            int affectedRows = pstmt.executeUpdate();
            return affectedRows > 0;
        } catch (SQLException e) {
            System.err.println("Error resetting unread count and setting last read message ID: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }


    // Delete (Remove Chat Participants)
    public boolean deleteChatParticipant(int chatId, int userId) {
        String sql = "DELETE FROM chat_participants WHERE chat_id = ? AND user_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, chatId);
            pstmt.setInt(2, userId);
            int affectedRows = pstmt.executeUpdate();
            return affectedRows > 0;
        } catch (SQLException e) {
            System.err.println("Error deleting chat participant: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // Helper method to map ResultSet to ChatParticipant object
    private ChatParticipant mapResultSetToChatParticipant(ResultSet rs) throws SQLException {
        ChatParticipant participant = new ChatParticipant();
        participant.setId(rs.getInt("id"));
        participant.setChatId(rs.getInt("chat_id"));
        participant.setUserId(rs.getInt("user_id"));
        participant.setRole(rs.getString("role"));
        participant.setUnreadCount(rs.getInt("unread_count"));

        // Handle nullable last_read_message_id
        int lastReadMessageId = rs.getInt("last_read_message_id");
        if (rs.wasNull()) {
            participant.setLastReadMessageId(null);
        } else {
            participant.setLastReadMessageId(lastReadMessageId);
        }
        participant.setJoinedAt(rs.getTimestamp("joined_at").toLocalDateTime());
        return participant;
    }

    // ----------------------------

    public Optional<ChatParticipant> getChatParticipantById(int id) throws SQLException {
        String sql = "SELECT id, chat_id, user_id, role, unread_count, last_read_message_id, joined_at FROM chat_participants WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToChatParticipant(rs));
                }
            }
        }
        return Optional.empty();
    }


    public Optional<ChatParticipant> getChatParticipant(int user_id ,int chat_id ) throws SQLException {
        //SELECT id, chat_id, user_id, role, unread_count, last_read_message_id, joined_at FROM chat_participants WHERE user_id = 19 and chat_id = 22
        String sql = "SELECT id, chat_id, user_id, role, unread_count, last_read_message_id, joined_at FROM chat_participants WHERE user_id = ? AND chat_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, user_id);
            stmt.setInt(2, chat_id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToChatParticipant(rs));
                }
            }
        }
        return Optional.empty();
    }

    // New method: isUserParticipant(chatId, currentUserId)
    public boolean isUserParticipant(int chatId, int userId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM chat_participants WHERE chat_id = ? AND user_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, chatId);
            stmt.setInt(2, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        }
        return false;
    }

    // New method: getChatParticipants(chatId)
    public List<ChatParticipant> getChatParticipants(int chatId) throws SQLException {
        List<ChatParticipant> participants = new ArrayList<>();
        String sql = "SELECT id, chat_id, user_id, role, unread_count, last_read_message_id, joined_at FROM chat_participants WHERE chat_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, chatId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    participants.add(mapResultSetToChatParticipant(rs));
                }
            }
        }
        return participants;
    }

//    public int createChatParticipant(ChatParticipant participant) throws SQLException {
//        String sql = "INSERT INTO chat_participants (chat_id, user_id, role, unread_count) VALUES (?, ?, ?, ?)";
//        try (Connection conn = DatabaseConnection.getConnection();
//             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
//            stmt.setInt(1, participant.getChatId());
//            stmt.setInt(2, participant.getUserId());
//            stmt.setString(3, participant.getRole());
//            stmt.setInt(4, participant.getUnreadCount());
//            int affectedRows = stmt.executeUpdate();
//
//            if (affectedRows == 0) {
//                throw new SQLException("Creating chat participant failed, no rows affected.");
//            }
//
//            try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
//                if (generatedKeys.next()) {
//                    return generatedKeys.getInt(1);
//                } else {
//                    throw new SQLException("Creating chat participant failed, no ID obtained.");
//                }
//            }
//        } catch (SQLException e) {
//            // Catch unique constraint violation (user_id, chat_id)
//            if (e.getSQLState().equals("23000") && e.getMessage().contains("Duplicate entry")) {
//                System.err.println("User " + participant.getUserId() + " is already a participant of chat " + participant.getChatId());
//                return -1; // Indicate failure due to duplicate
//            }
//            throw e; // Re-throw other SQL exceptions
//        }
//    }

    // New method: updateChatParticipant(existingParticipant)
    public boolean updateChatParticipant(ChatParticipant participant) throws SQLException {
        String sql = "UPDATE chat_participants SET role = ?, unread_count = ?, last_read_message_id = ? WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, participant.getRole());
            stmt.setInt(2, participant.getUnreadCount());
            if (participant.getLastReadMessageId() != null) {
                stmt.setInt(3, participant.getLastReadMessageId());
            } else {
                stmt.setNull(3, Types.INTEGER);
            }
            stmt.setInt(4, participant.getId());
            return stmt.executeUpdate() > 0;
        }
    }

    // New method: deleteChatParticipant(participantId) - based on participant entry ID
    public boolean deleteChatParticipant(int participantId) throws SQLException {
        String sql = "DELETE FROM chat_participants WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, participantId);
            return stmt.executeUpdate() > 0;
        }
    }

//    // Alternative: deleteChatParticipant by (chatId, userId) pair (more common for "leaving a chat")
//    public boolean deleteChatParticipant(int chatId, int userId) throws SQLException {
//        String sql = "DELETE FROM chat_participants WHERE chat_id = ? AND user_id = ?";
//        try (Connection conn = DatabaseConnection.getConnection();
//             PreparedStatement stmt = conn.prepareStatement(sql)) {
//            stmt.setInt(1, chatId);
//            stmt.setInt(2, userId);
//            return stmt.executeUpdate() > 0;
//        }
//    }
//
//    private ChatParticipant mapResultSetToChatParticipant(ResultSet rs) throws SQLException {
//        ChatParticipant participant = new ChatParticipant();
//        participant.setId(rs.getInt("id"));
//        participant.setChatId(rs.getInt("chat_id"));
//        participant.setUserId(rs.getInt("user_id"));
//        participant.setRole(rs.getString("role"));
//        participant.setUnreadCount(rs.getInt("unread_count"));
//
//        int lastReadMessageId = rs.getInt("last_read_message_id");
//        if (rs.wasNull()) { // Check if the last_read_message_id was NULL in DB
//            participant.setLastReadMessageId(null);
//        } else {
//            participant.setLastReadMessageId(lastReadMessageId);
//        }
//
//        participant.setJoinedAt(rs.getTimestamp("joined_at").toLocalDateTime());
//        return participant;
//    }

}
