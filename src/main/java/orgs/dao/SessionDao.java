package orgs.dao;

import orgs.model.Session;
import orgs.utils.DatabaseConnection;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


public class SessionDao {

    // Create (Add a New Session)
    public int createSession(Session session) {
        String sql = "INSERT INTO sessions (user_id, device_token, is_active) VALUES (?, ?, ?)";
        int generatedId = -1;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            pstmt.setInt(1, session.getUserId());
            pstmt.setString(2, session.getDeviceToken());
            pstmt.setBoolean(3, session.isActive());

            int affectedRows = pstmt.executeUpdate();
            if (affectedRows > 0) {
                try (ResultSet rs = pstmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        generatedId = rs.getInt(1);
                        session.setId(generatedId);
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Error creating session: " + e.getMessage());
            e.printStackTrace();
        }
        return generatedId;
    }

    // Read (Retrieve Sessions)
    public List<Session> getActiveSessionsByUserId(int userId) {
        List<Session> sessions = new ArrayList<>();
        String sql = "SELECT * FROM sessions WHERE user_id = ? AND is_active = TRUE";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    sessions.add(mapResultSetToSession(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting active sessions by user ID: " + e.getMessage());
            e.printStackTrace();
        }
        return sessions;
    }

    public Optional<Session> getSessionByDeviceToken(String deviceToken) {
        String sql = "SELECT * FROM sessions WHERE device_token = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, deviceToken);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToSession(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting session by device token: " + e.getMessage());
            e.printStackTrace();
        }
        return Optional.empty();
    }

    // Update (Modify Session Status)
    public boolean deactivateSession(int sessionId) {
        String sql = "UPDATE sessions SET is_active = FALSE, last_active_at = ? WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
            pstmt.setInt(2, sessionId);
            int affectedRows = pstmt.executeUpdate();
            return affectedRows > 0;
        } catch (SQLException e) {
            System.err.println("Error deactivating session: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public boolean updateSessionLastActive(int userId, String deviceToken) {
        String sql = "UPDATE sessions SET last_active_at = ? WHERE user_id = ? AND device_token = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
            pstmt.setInt(2, userId);
            pstmt.setString(3, deviceToken);
            int affectedRows = pstmt.executeUpdate();
            return affectedRows > 0;
        } catch (SQLException e) {
            System.err.println("Error updating session last active time: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // Delete (Remove a Session)
    public boolean deleteSession(int id) {
        String sql = "DELETE FROM sessions WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, id);
            int affectedRows = pstmt.executeUpdate();
            return affectedRows > 0;
        } catch (SQLException e) {
            System.err.println("Error deleting session: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public boolean deleteSessionsByUserId(int userId) {
        String sql = "DELETE FROM sessions WHERE user_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, userId);
            int affectedRows = pstmt.executeUpdate();
            return affectedRows > 0;
        } catch (SQLException e) {
            System.err.println("Error deleting sessions for user: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // Helper method to map ResultSet to Session object
    private Session mapResultSetToSession(ResultSet rs) throws SQLException {
        Session session = new Session();
        session.setId(rs.getInt("id"));
        session.setUserId(rs.getInt("user_id"));
        session.setDeviceToken(rs.getString("device_token"));
        session.setActive(rs.getBoolean("is_active"));
        session.setLastActiveAt(rs.getTimestamp("last_active_at").toLocalDateTime());
        session.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return session;
    }
}
