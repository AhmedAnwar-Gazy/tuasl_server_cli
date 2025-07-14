package orgs.dao;

import orgs.model.Contact;
import orgs.model.User;
import orgs.utils.DatabaseConnection;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ContactDao {

    // Create (Add a Contact)
//    public boolean createContact(Contact contact) {
//        String sql = "INSERT INTO contacts (user_id, contact_user_id, alias_name) VALUES (?, ?, ?)";
//        try (Connection conn = DatabaseConnection.getConnection();
//             PreparedStatement pstmt = conn.prepareStatement(sql)) {
//
//            pstmt.setInt(1, contact.getUserId());
//            pstmt.setInt(2, contact.getContactUserId());
//            pstmt.setString(3, contact.getAliasName());
//
//            int affectedRows = pstmt.executeUpdate();
//            return affectedRows > 0;
//        } catch (SQLException e) {
//            System.err.println("Error creating contact: " + e.getMessage());
//            e.printStackTrace();
//            return false;
//        }
//    }

    // Read (Retrieve Contacts)
    public List<User> getContactsForUser(int userId) {
        List<User> contacts = new ArrayList<>();
        String sql = "SELECT u.id, u.username, u.phone_number, c.alias_name " +
                "FROM contacts c " +
                "JOIN users u ON c.contact_user_id = u.id " +
                "WHERE c.user_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    User contactUser = new User();
                    contactUser.setId(rs.getInt("id"));
                    contactUser.setUsername(rs.getString("username"));
                    contactUser.setPhoneNumber(rs.getString("phone_number"));
                    // You might want to extend the Contact model or create a DTO to hold alias_name
                    // For now, printing it.
                    System.out.println("Contact: " + contactUser.getUsername() + ", Alias: " + rs.getString("alias_name"));
                    contacts.add(contactUser);
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting contacts for user: " + e.getMessage());
            e.printStackTrace();
        }
        return contacts;
    }

    public boolean isUserContact(int userId, int contactUserId) {
        String sql = "SELECT EXISTS (SELECT 1 FROM contacts WHERE user_id = ? AND contact_user_id = ?) AS is_contact";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, userId);
            pstmt.setInt(2, contactUserId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getBoolean("is_contact");
                }
            }
        } catch (SQLException e) {
            System.err.println("Error checking if user is contact: " + e.getMessage());
            e.printStackTrace();
        }
        return false;
    }

    // Update (Modify Contact Information)
    public boolean updateContactAlias(int userId, int contactUserId, String newAliasName) {
        String sql = "UPDATE contacts SET alias_name = ? WHERE user_id = ? AND contact_user_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, newAliasName);
            pstmt.setInt(2, userId);
            pstmt.setInt(3, contactUserId);
            int affectedRows = pstmt.executeUpdate();
            return affectedRows > 0;
        } catch (SQLException e) {
            System.err.println("Error updating contact alias: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // Delete (Remove a Contact)
    public boolean deleteContact(int userId, int contactUserId) {
        String sql = "DELETE FROM contacts WHERE user_id = ? AND contact_user_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, userId);
            pstmt.setInt(2, contactUserId);
            int affectedRows = pstmt.executeUpdate();
            return affectedRows > 0;
        } catch (SQLException e) {
            System.err.println("Error deleting contact: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // Helper method to map ResultSet to Contact object (if you need the Contact ID itself)
    private Contact mapResultSetToContact(ResultSet rs) throws SQLException {
        Contact contact = new Contact();
        contact.setId(rs.getInt("id"));
        contact.setUserId(rs.getInt("user_id"));
        contact.setContactUserId(rs.getInt("contact_user_id"));
        contact.setAliasName(rs.getString("alias_name"));
        contact.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return contact;
    }
    public int createContact(Contact contact) throws SQLException {
        String sql = "INSERT INTO contacts (user_id, contact_user_id) VALUES (?, ?)";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, contact.getUserId());
            stmt.setInt(2, contact.getContactUserId());
            int affectedRows = stmt.executeUpdate();

            if (affectedRows == 0) {
                throw new SQLException("Creating contact failed, no rows affected.");
            }

            try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    return generatedKeys.getInt(1);
                } else {
                    throw new SQLException("Creating contact failed, no ID obtained.");
                }
            }
        }
    }

    public Optional<Contact> getContact(int userId, int contactUserId) throws SQLException {
        String sql = "SELECT id, user_id, contact_user_id FROM contacts WHERE user_id = ? AND contact_user_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            stmt.setInt(2, contactUserId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new Contact(
                            rs.getInt("id"),
                            rs.getInt("user_id"),
                            rs.getInt("contact_user_id")
                    ));
                }
            }
        }
        return Optional.empty();
    }

    public List<User> getUserContacts(int userId) throws SQLException {
        List<User> contacts = new ArrayList<>();
        // Join with users table to get contact user details
        String sql = "SELECT u.id, u.username, u.first_name, u.last_name, u.phone_number, u.bio, u.profile_picture_url, u.is_online, u.last_seen_at " +
                "FROM contacts c JOIN users u ON c.contact_user_id = u.id " +
                "WHERE c.user_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    User user = new User();
                    user.setId(rs.getInt("id"));
                    user.setUsername(rs.getString("username"));
                    user.setFirstName(rs.getString("first_name"));
                    user.setLastName(rs.getString("last_name"));
                    user.setPhoneNumber(rs.getString("phone_number"));
                    user.setBio(rs.getString("bio"));
                    user.setProfilePictureUrl(rs.getString("profile_picture_url"));
                    user.setOnline(rs.getBoolean("is_online"));
                    // Assuming last_seen_at can be null in DB
                    user.setLastSeenAt(rs.getTimestamp("last_seen_at") != null ?
                            rs.getTimestamp("last_seen_at").toLocalDateTime() : null);
                    contacts.add(user);
                }
            }
        }
        return contacts;
    }



}
