package orgs.dao;

import orgs.model.Chat;
import orgs.utils.DatabaseConnection;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ChatDao {

    // Create (Add a New Chat)
    public int createChat(Chat chat) {
        String sql = "INSERT INTO chats (chat_type, chat_name, chat_picture_url, chat_description, public_link, creator_id) VALUES (?, ?, ?, ?, ?, ?)";
        int generatedId = -1;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            pstmt.setString(1, chat.getChatType());
            pstmt.setString(2, chat.getChatName());
            pstmt.setString(3, chat.getChatPictureUrl());
            pstmt.setString(4, chat.getChatDescription());
            pstmt.setString(5, chat.getPublicLink());
            pstmt.setInt(6, chat.getCreatorId());

            int affectedRows = pstmt.executeUpdate();
            if (affectedRows > 0) {
                try (ResultSet rs = pstmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        generatedId = rs.getInt(1);
                        chat.setId(generatedId);
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Error creating chat: " + e.getMessage());
            e.printStackTrace();
        }
        return generatedId;
    }

    // Read (Retrieve Chat Information)
    public List<Chat> getAllChats() {
        List<Chat> chats = new ArrayList<>();
        String sql = "SELECT * FROM chats";
        try (Connection conn = DatabaseConnection.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                chats.add(mapResultSetToChat(rs));
            }
        } catch (SQLException e) {
            System.err.println("Error getting all chats: " + e.getMessage());
            e.printStackTrace();
        }
        return chats;
    }

    public Optional<Chat> getChatById(int id) {
        String sql = "SELECT * FROM chats WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, id);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToChat(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting chat by ID: " + e.getMessage());
            e.printStackTrace();
        }
        return Optional.empty();
    }

    public List<Chat> getGroupsCreatedByUser(int creatorId) {
        List<Chat> chats = new ArrayList<>();
        String sql = "SELECT * FROM chats WHERE creator_id = ? AND chat_type = 'group'";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, creatorId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    chats.add(mapResultSetToChat(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting groups created by user: " + e.getMessage());
            e.printStackTrace();
        }
        return chats;
    }

    public Optional<Chat> getChatByPublicLink(String publicLink) {
        String sql = "SELECT * FROM chats WHERE public_link = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, publicLink);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToChat(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting chat by public link: " + e.getMessage());
            e.printStackTrace();
        }
        return Optional.empty();
    }

    // Update (Modify Chat Information)
    public boolean updateChat(Chat chat) {
        String sql = "UPDATE chats SET chat_name = ?, chat_picture_url = ?, chat_description = ?, public_link = ? WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, chat.getChatName());
            pstmt.setString(2, chat.getChatPictureUrl());
            pstmt.setString(3, chat.getChatDescription());
            pstmt.setString(4, chat.getPublicLink());
            pstmt.setInt(5, chat.getId());

            int affectedRows = pstmt.executeUpdate();
            return affectedRows > 0;
        } catch (SQLException e) {
            System.err.println("Error updating chat: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // Delete (Remove a Chat)
    public boolean deleteChat(int id) {
        String sql = "DELETE FROM chats WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, id);
            int affectedRows = pstmt.executeUpdate();
            return affectedRows > 0;
        } catch (SQLException e) {
            System.err.println("Error deleting chat: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // Helper method to map ResultSet to Chat object
    private Chat mapResultSetToChat(ResultSet rs) throws SQLException {
        Chat chat = new Chat();
        chat.setId(rs.getInt("id"));
        chat.setChatType(rs.getString("chat_type"));
        chat.setChatName(rs.getString("chat_name"));
        chat.setChatPictureUrl(rs.getString("chat_picture_url"));
        chat.setChatDescription(rs.getString("chat_description"));
        chat.setPublicLink(rs.getString("public_link"));
        chat.setCreatorId(rs.getInt("creator_id"));
        chat.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        chat.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
        return chat;
    }
    // --------------------
//    public Optional<Chat> getChatById(int id) throws SQLException {
//        String sql = "SELECT id, chat_type, chat_name, chat_picture_url, chat_description, public_link, creator_id, created_at, updated_at FROM chats WHERE id = ?";
//        try (Connection conn = DatabaseConnection.getConnection();
//             PreparedStatement stmt = conn.prepareStatement(sql)) {
//            stmt.setInt(1, id);
//            try (ResultSet rs = stmt.executeQuery()) {
//                if (rs.next()) {
//                    return Optional.of(mapResultSetToChat(rs));
//                }
//            }
//        }
//        return Optional.empty();
//    }

    // New method: getUserChats(currentUserId)
    public List<Chat> getUserChats(int userId) throws SQLException {
        List<Chat> chats = new ArrayList<>();
        // Join with chat_participants to get chats the user is a part of
        String sql = "SELECT c.id, c.chat_type, c.chat_name, c.chat_picture_url, c.chat_description, c.public_link, c.creator_id, c.created_at, c.updated_at " +
                "FROM chats c JOIN chat_participants cp ON c.id = cp.chat_id " +
                "WHERE cp.user_id = ? " +
                "ORDER BY c.updated_at DESC"; // Order by most recent activity
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    chats.add(mapResultSetToChat(rs));
                }
            }
        }
        return chats;
    }

//    public int createChat(Chat chat) throws SQLException {
//        String sql = "INSERT INTO chats (chat_type, chat_name, chat_picture_url, chat_description, public_link, creator_id) VALUES (?, ?, ?, ?, ?, ?)";
//        try (Connection conn = DatabaseConnection.getConnection();
//             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
//            stmt.setString(1, chat.getChatType());
//            stmt.setString(2, chat.getChatName());
//            stmt.setString(3, chat.getChatPictureUrl());
//            stmt.setString(4, chat.getChatDescription());
//            stmt.setString(5, chat.getPublicLink());
//            stmt.setInt(6, chat.getCreatorId());
//            int affectedRows = stmt.executeUpdate();
//
//            if (affectedRows == 0) {
//                throw new SQLException("Creating chat failed, no rows affected.");
//            }
//
//            try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
//                if (generatedKeys.next()) {
//                    return generatedKeys.getInt(1);
//                } else {
//                    throw new SQLException("Creating chat failed, no ID obtained.");
//                }
//            }
//        }
//    }
//
//    public boolean updateChat(Chat chat) throws SQLException {
//        String sql = "UPDATE chats SET chat_name = ?, chat_picture_url = ?, chat_description = ?, public_link = ?, chat_type = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
//        try (Connection conn = DatabaseConnection.getConnection();
//             PreparedStatement stmt = conn.prepareStatement(sql)) {
//            stmt.setString(1, chat.getChatName());
//            stmt.setString(2, chat.getChatPictureUrl());
//            stmt.setString(3, chat.getChatDescription());
//            stmt.setString(4, chat.getPublicLink());
//            stmt.setString(5, chat.getChatType());
//            stmt.setInt(6, chat.getId());
//            return stmt.executeUpdate() > 0;
//        }
//    }
//
//    public boolean deleteChat(int id) throws SQLException {
//        // Due to ON DELETE CASCADE on chat_participants and messages,
//        // deleting the chat will automatically delete related entries.
//        String sql = "DELETE FROM chats WHERE id = ?";
//        try (Connection conn = DatabaseConnection.getConnection();
//             PreparedStatement stmt = conn.prepareStatement(sql)) {
//            stmt.setInt(1, id);
//            return stmt.executeUpdate() > 0;
//        }
//    }
//
//    private Chat mapResultSetToChat(ResultSet rs) throws SQLException {
//        Chat chat = new Chat();
//        chat.setId(rs.getInt("id"));
//        chat.setChatType(rs.getString("chat_type"));
//        chat.setChatName(rs.getString("chat_name"));
//        chat.setChatPictureUrl(rs.getString("chat_picture_url"));
//        chat.setChatDescription(rs.getString("chat_description"));
//        chat.setPublicLink(rs.getString("public_link"));
//        chat.setCreatorId(rs.getInt("creator_id"));
//        chat.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
//        chat.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
//        return chat;
//    }
}
