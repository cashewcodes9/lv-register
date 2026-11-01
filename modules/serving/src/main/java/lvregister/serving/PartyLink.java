package lvregister.serving;

/**
 * A role of a party in a company, for the company network.
 *
 * @param partyKey {@code person:<person key>} or {@code company:<registration number>}
 */
public record PartyLink(String partyKey, String regNumber, String roleKind) {

    public static String personKey(String key) {
        return "person:" + key;
    }

    public static String companyKey(String regNumber) {
        return "company:" + regNumber;
    }
}
