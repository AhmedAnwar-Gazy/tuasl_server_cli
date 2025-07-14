package orgs.dao;

import orgs.model.UserSetting;
import orgs.utils.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

public class UserSettingsDao {

    // Create (Add User Settings)
    public boolean createUserSettings(UserSetting settings) {
        String sql = "INSERT INTO user_settings (user_id, privacy_phone_number, privacy_last_seen, privacy_profile_photo, privacy_groups_and_channels, notifications_private_chats, notifications_group_chats, notifications_channels) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, settings.getUserId()); // Ensure user_id is set in the UserSetting object
            pstmt.setString(2, settings.getPrivacyPhoneNumber());
            pstmt.setString(3, settings.getPrivacyLastSeen());
            pstmt.setString(4, settings.getPrivacyProfilePhoto());
            pstmt.setString(5, settings.getPrivacyGroupsAndChannels());
            pstmt.setBoolean(6, settings.isNotificationsPrivateChats());
            pstmt.setBoolean(7, settings.isNotificationsGroupChats());
            pstmt.setBoolean(8, settings.isNotificationsChannels());

            int affectedRows = pstmt.executeUpdate();
            return affectedRows > 0;
        } catch (SQLException e) {
            System.err.println("Error creating user settings: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // Read (Retrieve User Settings)
    public Optional<UserSetting> getUserSettingsByUserId(int userId) {
        String sql = "SELECT * FROM user_settings WHERE user_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToUserSetting(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting user settings by user ID: " + e.getMessage());
            e.printStackTrace();
        }
        return Optional.empty();
    }

    // Update (Modify User Settings)
    public boolean updateUserSettings(UserSetting settings) {
        String sql = "UPDATE user_settings SET privacy_phone_number = ?, privacy_last_seen = ?, privacy_profile_photo = ?, privacy_groups_and_channels = ?, notifications_private_chats = ?, notifications_group_chats = ?, notifications_channels = ? WHERE user_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, settings.getPrivacyPhoneNumber());
            pstmt.setString(2, settings.getPrivacyLastSeen());
            pstmt.setString(3, settings.getPrivacyProfilePhoto());
            pstmt.setString(4, settings.getPrivacyGroupsAndChannels());
            pstmt.setBoolean(5, settings.isNotificationsPrivateChats());
            pstmt.setBoolean(6, settings.isNotificationsGroupChats());
            pstmt.setBoolean(7, settings.isNotificationsChannels());
            pstmt.setInt(8, settings.getUserId());

            int affectedRows = pstmt.executeUpdate();
            return affectedRows > 0;
        } catch (SQLException e) {
            System.err.println("Error updating user settings: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // Delete (Remove User Settings)
    public boolean deleteUserSettings(int userId) {
        String sql = "DELETE FROM user_settings WHERE user_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, userId);
            int affectedRows = pstmt.executeUpdate();
            return affectedRows > 0;
        } catch (SQLException e) {
            System.err.println("Error deleting user settings: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // Helper method to map ResultSet to UserSetting object
    private UserSetting mapResultSetToUserSetting(ResultSet rs) throws SQLException {
        UserSetting settings = new UserSetting();
        settings.setUserId(rs.getInt("user_id"));
        settings.setPrivacyPhoneNumber(rs.getString("privacy_phone_number"));
        settings.setPrivacyLastSeen(rs.getString("privacy_last_seen"));
        settings.setPrivacyProfilePhoto(rs.getString("privacy_profile_photo"));
        settings.setPrivacyGroupsAndChannels(rs.getString("privacy_groups_and_channels"));
        settings.setNotificationsPrivateChats(rs.getBoolean("notifications_private_chats"));
        settings.setNotificationsGroupChats(rs.getBoolean("notifications_group_chats"));
        settings.setNotificationsChannels(rs.getBoolean("notifications_channels"));
        return settings;
    }
}
