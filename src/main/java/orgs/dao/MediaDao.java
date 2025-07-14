package orgs.dao;

import orgs.model.Media;
import orgs.utils.DatabaseConnection;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class MediaDao {

    // Create (Add New Media)
    public int createMedia(Media media) {
        String sql = "INSERT INTO media (file_path_or_url, thumbnail_url, file_size, media_type, uploaded_by_user_id) VALUES (?, ?, ?, ?, ?)";
        int generatedId = -1;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            pstmt.setString(1, media.getFilePathOrUrl());
            pstmt.setString(2, media.getThumbnailUrl());
            pstmt.setLong(3, media.getFileSize());
            pstmt.setString(4, media.getMediaType());
            pstmt.setInt(5, media.getUploadedByUserId());

            int affectedRows = pstmt.executeUpdate();
            if (affectedRows > 0) {
                try (ResultSet rs = pstmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        generatedId = rs.getInt(1);
                        media.setId(generatedId);
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Error creating media: " + e.getMessage());
            e.printStackTrace();
        }
        return generatedId;
    }

    // Read (Retrieve Media Information)
    public Optional<Media> getMediaById(int id) {
        String sql = "SELECT * FROM media WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, id);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToMedia(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting media by ID: " + e.getMessage());
            e.printStackTrace();
        }
        return Optional.empty();
    }

    public List<Media> getMediaByUploadedUserId(int uploadedByUserId) {
        List<Media> mediaList = new ArrayList<>();
        String sql = "SELECT * FROM media WHERE uploaded_by_user_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, uploadedByUserId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    mediaList.add(mapResultSetToMedia(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting media by uploaded user ID: " + e.getMessage());
            e.printStackTrace();
        }
        return mediaList;
    }

    // Update (Modify Media Information)
    public boolean updateMediaFilePath(int mediaId, String newFilePathOrUrl) {
        String sql = "UPDATE media SET file_path_or_url = ? WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, newFilePathOrUrl);
            pstmt.setInt(2, mediaId);

            int affectedRows = pstmt.executeUpdate();
            return affectedRows > 0;
        } catch (SQLException e) {
            System.err.println("Error updating media file path: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // Delete (Remove Media)
    public boolean deleteMedia(int id) {
        String sql = "DELETE FROM media WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, id);
            int affectedRows = pstmt.executeUpdate();
            return affectedRows > 0;
        } catch (SQLException e) {
            System.err.println("Error deleting media: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // Helper method to map ResultSet to Media object
    private Media mapResultSetToMedia(ResultSet rs) throws SQLException {
        Media media = new Media();
        media.setId(rs.getInt("id"));
        media.setFilePathOrUrl(rs.getString("file_path_or_url"));
        media.setThumbnailUrl(rs.getString("thumbnail_url"));
        media.setFileSize(rs.getLong("file_size"));
        media.setMediaType(rs.getString("media_type"));
        media.setUploadedByUserId(rs.getInt("uploaded_by_user_id"));
        media.setUploadedAt(rs.getTimestamp("uploaded_at").toLocalDateTime());
        return media;
    }

//
//    public static  void main(){
//        System.out.println("go");
//        MediaDao mddao = new MediaDao();
//        Media md = new Media();
//        md = mddao.getMediaById(5).get();
//        System.out.println(md.toString());
//    }



}
