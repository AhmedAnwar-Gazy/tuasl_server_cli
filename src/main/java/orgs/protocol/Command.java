// src/orgs/protocol/Command.java
package orgs.protocol;

public enum Command {
    // Authentication & User Management
    REGISTER,
    LOGIN,
    LOGOUT,
    GET_USER_PROFILE,
    UPDATE_USER_PROFILE,
    DELETE_USER,
    GET_ALL_USERS,

    // Chat Management
    CREATE_CHAT,
    GET_USER_CHATS,
    GET_CHAT_DETAILS,
    UPDATE_CHAT,
    DELETE_CHAT,

    // Message Management
    SEND_TEXT_MESSAGE, // Specific for plain text
    SEND_IMAGE,        // For images (will involve file transfer)
    SEND_VIDEO,        // For videos (will involve file transfer)
    SEND_VOICE_NOTE,   // For voice notes (will involve file transfer)
    SEND_FILE,         // For general files (will involve file transfer)
    GET_CHAT_UNREADMESSAGES,

    GET_CHAT_BY_ID,
    GET_USER_BY_PHONENUMBER,
    GET_USER_BY_ID,


    SEND_MESSAGE,
    GET_CHAT_MESSAGES,
    UPDATE_MESSAGE,
    DELETE_MESSAGE,
    MARK_MESSAGE_AS_READ,
    GET_FILE_BY_MEDIA,


    // Chat Participant Management
    ADD_CHAT_PARTICIPANT,
    GET_CHAT_PARTICIPANTS,
    UPDATE_CHAT_PARTICIPANT_ROLE, // Corrected from general UPDATE_CHAT_PARTICIPANT
    REMOVE_CHAT_PARTICIPANT,

    // Contact Management
    ADD_CONTACT,
    GET_CONTACTS,
    REMOVE_CONTACT,
    BLOCK_UNBLOCK_USER, // Client uses a single command for both block/unblock

    // Notification Management
    MY_NOTIFICATIONS, // Client uses MY_NOTIFICATIONS, server uses GET_USER_NOTIFICATIONS
    MARK_NOTIFICATION_AS_READ,
    DELETE_NOTIFICATION,

    // Video Call Management
    INITIATE_VIDEO_CALL,    // Client A to Server: "I want to call User B"
    VIDEO_CALL_OFFER,       // Server to Client B: "User A is calling you"
    VIDEO_CALL_ANSWER,      // Client B to Server: "I accept/reject User A's call"
    VIDEO_CALL_ACCEPTED,    // Server to Client A: "User B accepted" (includes B's UDP info)
    VIDEO_CALL_REJECTED,    // Server to Client A: "User B rejected"
    END_VIDEO_CALL,         // Client to Server: "End current call"
    VIDEO_CALL_ENDED,       // Server to Client: "Call has ended"

    // Optional: For advanced NAT traversal / ICE
    ICE_CANDIDATE,          // Exchange of network candidates for direct connection
    SDP_OFFER,              // Session Description Protocol offer
    SDP_ANSWER,             // Session Description Protocol answer


    // Other
    UNKNOWN_COMMAND




}