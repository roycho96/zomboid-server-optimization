public final class VerifyRecipeSettings {
    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("roy.pzcore.CoreSettings");
        if (type.getField("PACKET_BATCHING").getBoolean(null)) throw new AssertionError("Batching must stay absent");
        if (type.getField("EMPTY_REMOVAL_GUARD").getBoolean(null) != Boolean.parseBoolean(args[0])) throw new AssertionError("Empty guard setting differs");
        if (type.getField("PACKET_RATE_WINDOWS").getBoolean(null) != Boolean.parseBoolean(args[1])) throw new AssertionError("Rate window setting differs");
        boolean vocals = type.getField("SERVER_ZOMBIE_VOCALS_GUARD").getBoolean(null);
        if (args.length > 2 && vocals != Boolean.parseBoolean(args[2])) throw new AssertionError("Server vocals guard setting differs");
        boolean membership = type.getField("SERVER_ZOMBIE_MEMBERSHIP_HINTS").getBoolean(null);
        if (args.length > 3 && membership != Boolean.parseBoolean(args[3])) throw new AssertionError("Server membership setting differs");
        if (args.length > 4 && type.getField("SERVER_ZOMBIE_REQUEST_INDEX").getBoolean(null) != Boolean.parseBoolean(args[4])) throw new AssertionError("Request index setting differs");
        if (args.length > 5 && type.getField("SERVER_DIRECTION_MEMO").getBoolean(null) != Boolean.parseBoolean(args[5])) throw new AssertionError("Direction memo setting differs");
        System.out.println("settings verified");
    }
}
