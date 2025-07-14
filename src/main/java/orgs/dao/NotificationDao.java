package orgs.dao;

import orgs.model.Notification;
import orgs.utils.DatabaseConnection;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


public class NotificationDao {

    // Create (Send a Notification)
    public int createNotification(Notification notification) {
        String sql = "INSERT INTO notifications (recipient_user_id, message, event_type, related_chat_id) VALUES (?, ?, ?, ?)";
        int generatedId = -1;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            pstmt.setInt(1, notification.getRecipientUserId());
            pstmt.setString(2, notification.getMessage());
            pstmt.setString(3, notification.getEventType());
            pstmt.setObject(4, notification.getRelatedChatId(), Types.INTEGER); // Handle nullable Integer

            int affectedRows = pstmt.executeUpdate();
            if (affectedRows > 0) {
                try (ResultSet rs = pstmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        generatedId = rs.getInt(1);
                        notification.setId(generatedId);
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Error creating notification: " + e.getMessage());
            e.printStackTrace();
        }
        return generatedId;
    }

    // Read (Retrieve Notifications)
    public List<Notification> getUnreadNotificationsForUser(int recipientUserId) {
        List<Notification> notifications = new ArrayList<>();
        String sql = "SELECT * FROM notifications WHERE recipient_user_id = ? AND is_read = FALSE ORDER BY timestamp DESC";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, recipientUserId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    notifications.add(mapResultSetToNotification(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting unread notifications: " + e.getMessage());
            e.printStackTrace();
        }
        return notifications;
    }

    public List<Notification> getAllNotificationsForUser(int recipientUserId, int limit) {
        List<Notification> notifications = new ArrayList<>();
        String sql = "SELECT * FROM notifications WHERE recipient_user_id = ? ORDER BY timestamp DESC LIMIT ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, recipientUserId);
            pstmt.setInt(2, limit);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    notifications.add(mapResultSetToNotification(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting all notifications for user: " + e.getMessage());
            e.printStackTrace();
        }
        return notifications;
    }

    // Update (Mark Notification as Read)
    public boolean markNotificationAsRead(int notificationId) {
        String sql = "UPDATE notifications SET is_read = TRUE WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, notificationId);
            int affectedRows = pstmt.executeUpdate();
            return affectedRows > 0;
        } catch (SQLException e) {
            System.err.println("Error marking notification as read: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public boolean markAllNotificationsAsReadForUser(int recipientUserId) {
        String sql = "UPDATE notifications SET is_read = TRUE WHERE recipient_user_id = ? AND is_read = FALSE";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, recipientUserId);
            int affectedRows = pstmt.executeUpdate();
            return affectedRows > 0;
        } catch (SQLException e) {
            System.err.println("Error marking all notifications as read for user: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // Delete (Remove Notifications)
    public boolean deleteNotification(int id) {
        String sql = "DELETE FROM notifications WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, id);
            int affectedRows = pstmt.executeUpdate();
            return affectedRows > 0;
        } catch (SQLException e) {
            System.err.println("Error deleting notification: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public boolean deleteReadNotificationsForUser(int recipientUserId) {
        String sql = "DELETE FROM notifications WHERE recipient_user_id = ? AND is_read = TRUE";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, recipientUserId);
            int affectedRows = pstmt.executeUpdate();
            return affectedRows > 0;
        } catch (SQLException e) {
            System.err.println("Error deleting read notifications for user: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // Helper method to map ResultSet to Notification object
    private Notification mapResultSetToNotification(ResultSet rs) throws SQLException {
        Notification notification = new Notification();
        notification.setId(rs.getInt("id"));
        notification.setRecipientUserId(rs.getInt("recipient_user_id"));
        notification.setMessage(rs.getString("message"));
        notification.setEventType(rs.getString("event_type"));

        int relatedChatId = rs.getInt("related_chat_id");
        if (rs.wasNull()) {
            notification.setRelatedChatId(null);
        } else {
            notification.setRelatedChatId(relatedChatId);
        }
        notification.setRead(rs.getBoolean("is_read"));
        notification.setTimestamp(rs.getTimestamp("timestamp").toLocalDateTime());
        return notification;
    }

    //-----------------------------

//
//    public int createNotification(Notification notification) throws SQLException {
//        String sql = "INSERT INTO notifications (recipient_user_id, message, event_type, related_chat_id, is_read, timestamp) VALUES (?, ?, ?, ?, ?, ?)";
//        try (Connection conn = DatabaseConnection.getConnection();
//             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
//            stmt.setInt(1, notification.getRecipientUserId());
//            stmt.setString(2, notification.getMessage());
//            stmt.setString(3, notification.getEventType());
//            if (notification.getRelatedChatId() != null) {
//                stmt.setInt(4, notification.getRelatedChatId());
//            } else {
//                stmt.setNull(4, java.sql.Types.INTEGER);
//            }
//            stmt.setBoolean(5, notification.isRead());
//            stmt.setTimestamp(6, Timestamp.valueOf(notification.getTimestamp()));  ;// Renamed from timestamp to sentAt in model?
//            int affectedRows = stmt.executeUpdate();
//
//            if (affectedRows == 0) {
//                throw new SQLException("Creating notification failed, no rows affected.");
//            }
//
//            try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
//                if (generatedKeys.next()) {
//                    return generatedKeys.getInt(1);
//                } else {
//                    throw new SQLException("Creating notification failed, no ID obtained.");
//                }
//            }
//        }
//    }

    // New method: getNotificationsByUserId(currentUserId)
    public List<Notification> getNotificationsByUserId(int userId) throws SQLException {
        List<Notification> notifications = new ArrayList<>();
        String sql = "SELECT id, recipient_user_id, message, event_type, related_chat_id, is_read, timestamp FROM notifications WHERE recipient_user_id = ? ORDER BY timestamp DESC";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    notifications.add(mapResultSetToNotification(rs));
                }
            }
        }
        return notifications;
    }

    // New method: getNotificationById(notificationId)
    public Optional<Notification> getNotificationById(int id) throws SQLException {
        String sql = "SELECT id, recipient_user_id, message, event_type, related_chat_id, is_read, timestamp FROM notifications WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToNotification(rs));
                }
            }
        }
        return Optional.empty();
    }

//    // New method: markNotificationAsRead(notificationId)
//    public boolean markNotificationAsRead(int id) throws SQLException {
//        String sql = "UPDATE notifications SET is_read = TRUE WHERE id = ?";
//        try (Connection conn = DatabaseConnection.getConnection();
//             PreparedStatement stmt = conn.prepareStatement(sql)) {
//            stmt.setInt(1, id);
//            return stmt.executeUpdate() > 0;
//        }
//    }
//
//    // New method: deleteNotification(notificationId)
//    public boolean deleteNotification(int id) throws SQLException {
//        String sql = "DELETE FROM notifications WHERE id = ?";
//        try (Connection conn = DatabaseConnection.getConnection();
//             PreparedStatement stmt = conn.prepareStatement(sql)) {
//            stmt.setInt(1, id);
//            return stmt.executeUpdate() > 0;
//        }
//    }
//
//    private Notification mapResultSetToNotification(ResultSet rs) throws SQLException {
//        Notification notification = new Notification();
//        notification.setId(rs.getInt("id"));
//        notification.setRecipientId(rs.getInt("recipient_user_id"));
//        notification.setMessage(rs.getString("message"));
//        notification.setEventType(rs.getString("event_type"));
//
//        int relatedChatId = rs.getInt("related_chat_id");
//        if (rs.wasNull()) {
//            notification.setRelatedChatId(null);
//        } else {
//            notification.setRelatedChatId(relatedChatId);
//        }
//
//        notification.setRead(rs.getBoolean("is_read"));
//        notification.setSentAt(rs.getTimestamp("timestamp").toLocalDateTime());
//        return notification;
//    }
}
