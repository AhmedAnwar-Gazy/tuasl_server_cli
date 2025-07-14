package orgs.dao;

import orgs.model.BlockedUser;
import orgs.model.User;
import orgs.utils.DatabaseConnection;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class BlockedUserDao {

    // Create (Block a User)
    public boolean blockUser(BlockedUser blockedUser) {
        String sql = "INSERT INTO blocked_users (blocker_id, blocked_id) VALUES (?, ?)";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, blockedUser.getBlockerId());
            pstmt.setInt(2, blockedUser.getBlockedId());

            int affectedRows = pstmt.executeUpdate();
            return affectedRows > 0;
        } catch (SQLException e) {
            // Handle unique constraint violation if user already blocked
            if (e instanceof SQLIntegrityConstraintViolationException) {
                System.err.println("User " + blockedUser.getBlockerId() + " has already blocked user " + blockedUser.getBlockedId());
            } else {
                System.err.println("Error blocking user: " + e.getMessage());
                e.printStackTrace();
            }
            return false;
        }
    }

    // Read (Retrieve Blocked Users)
    public List<User> getBlockedUsersByBlockerId(int blockerId) {
        List<User> blockedUsers = new ArrayList<>();
        String sql = "SELECT u.id, u.username, u.phone_number " +
                "FROM blocked_users bu " +
                "JOIN users u ON bu.blocked_id = u.id " +
                "WHERE bu.blocker_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, blockerId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    User user = new User();
                    user.setId(rs.getInt("id"));
                    user.setUsername(rs.getString("username"));
                    user.setPhoneNumber(rs.getString("phone_number"));
                    blockedUsers.add(user);
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting blocked users: " + e.getMessage());
            e.printStackTrace();
        }
        return blockedUsers;
    }

    public boolean isUserBlocked(int blockerId, int blockedId) {
        String sql = "SELECT EXISTS (SELECT 1 FROM blocked_users WHERE blocker_id = ? AND blocked_id = ?) AS is_blocked";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, blockerId);
            pstmt.setInt(2, blockedId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getBoolean("is_blocked");
                }
            }
        } catch (SQLException e) {
            System.err.println("Error checking if user is blocked: " + e.getMessage());
            e.printStackTrace();
        }
        return false;
    }

    // Delete (Unblock a User)
    public boolean unblockUser(int blockerId, int blockedId) {
        String sql = "DELETE FROM blocked_users WHERE blocker_id = ? AND blocked_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, blockerId);
            pstmt.setInt(2, blockedId);
            int affectedRows = pstmt.executeUpdate();
            return affectedRows > 0;
        } catch (SQLException e) {
            System.err.println("Error unblocking user: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // Helper method to map ResultSet to BlockedUser object (if needed)
    private BlockedUser mapResultSetToBlockedUser(ResultSet rs) throws SQLException {
        BlockedUser blockedUser = new BlockedUser();
        blockedUser.setBlockerId(rs.getInt("blocker_id"));
        blockedUser.setBlockedId(rs.getInt("blocked_id"));
        blockedUser.setBlockedAt(rs.getTimestamp("blocked_at").toLocalDateTime());
        return blockedUser;
    }


    // ----------------------
    // New method: createBlockedUser(blockedUser)
    public int createBlockedUser(BlockedUser blockedUser) throws SQLException {
        String sql = "INSERT INTO blocked_users (blocker_id, blocked_id, blocked_at) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, blockedUser.getBlockerId());
            stmt.setInt(2, blockedUser.getBlockedId());
            stmt.setTimestamp(3, Timestamp.valueOf(blockedUser.getBlockedAt()));
            int affectedRows = stmt.executeUpdate();

            if (affectedRows == 0) {
                // This could happen if the user is already blocked (due to PRIMARY KEY constraint)
                return -1; // Indicate no new row was inserted
            }
            // For a table with a composite primary key and no auto-increment ID,
            // we typically return 1 for success or the number of affected rows.
            return affectedRows;
        } catch (SQLException e) {
            // Catch unique constraint violation (blocker_id, blocked_id)
            if (e.getSQLState().equals("23000") && e.getMessage().contains("Duplicate entry")) {
                System.err.println("User " + blockedUser.getBlockerId() + " has already blocked user " + blockedUser.getBlockedId());
                return -1; // Indicate already exists
            }
            throw e; // Re-throw other SQL exceptions
        }
    }

    // New method: deleteBlockedUser(currentUserId, targetUserId)
    public boolean deleteBlockedUser(int blockerId, int blockedId) throws SQLException {
        String sql = "DELETE FROM blocked_users WHERE blocker_id = ? AND blocked_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, blockerId);
            stmt.setInt(2, blockedId);
            return stmt.executeUpdate() > 0;
        }
    }

    // Optional: Check if a user is blocked
//    public boolean isUserBlocked(int blockerId, int blockedId) throws SQLException {
//        String sql = "SELECT COUNT(*) FROM blocked_users WHERE blocker_id = ? AND blocked_id = ?";
//        try (Connection conn = DatabaseConnection.getConnection();
//             PreparedStatement stmt = conn.prepareStatement(sql)) {
//            stmt.setInt(1, blockerId);
//            stmt.setInt(2, blockedId);
//            try (ResultSet rs = stmt.executeQuery()) {
//                if (rs.next()) {
//                    return rs.getInt(1) > 0;
//                }
//            }
//        }
//        return false;
//    }
}
