package com.supplierconsumer.realtime;

import java.util.ArrayList;
import java.util.List;

/**
 * Room names, matching the original exactly so existing clients keep receiving what they expect.
 *
 * <p>Notifications go to two rooms per target ({@code user:consumer:7} and {@code consumer:7}).
 * The pair is redundant -- both carry the same payload -- but clients may have subscribed to
 * either, so both are kept.
 *
 * <p>Rooms are per company or per buyer, not per connection, so everyone signed in to a company
 * shares its stream.
 */
public final class Rooms {

    private Rooms() {
    }

    public static String consumer(long consumerId) {
        return "consumer:" + consumerId;
    }

    public static String company(long companyId) {
        return "company:" + companyId;
    }

    public static String conversation(long consumerId, long companyId) {
        return "chat:" + consumerId + ":" + companyId;
    }

    public static String userConsumer(long consumerId) {
        return "user:consumer:" + consumerId;
    }

    public static String userCompany(long companyId) {
        return "user:company:" + companyId;
    }

    /** Where a chat message goes: both participants, plus the conversation room. */
    public static List<String> forChatMessage(long consumerId, long companyId) {
        List<String> rooms = new ArrayList<>(3);
        rooms.add(consumer(consumerId));
        rooms.add(company(companyId));
        rooms.add(conversation(consumerId, companyId));
        return rooms;
    }

    /** Where an order or issue update goes: both participants. */
    public static List<String> forParticipants(long consumerId, long companyId) {
        return List.of(consumer(consumerId), company(companyId));
    }

    public static List<String> forNotification(String targetType, Long consumerId, Long companyId) {
        if ("consumer".equals(targetType) && consumerId != null) {
            return List.of(userConsumer(consumerId), consumer(consumerId));
        }
        if ("company".equals(targetType) && companyId != null) {
            return List.of(userCompany(companyId), company(companyId));
        }
        return List.of();
    }

    /** The unread badge goes to both spellings of the target's room, as the original does. */
    public static List<String> forUnread(String targetType, long consumerId, long companyId) {
        if ("consumer".equals(targetType)) {
            return List.of(userConsumer(consumerId), consumer(consumerId));
        }
        return List.of(userCompany(companyId), company(companyId));
    }
}
