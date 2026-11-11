package lvregister.serving;

/** Names of the stats and features the index step writes and the API reads. */
public final class ServingNames {

    /** Companies per legal family, legal form, status and region. */
    public static final String COMPANIES_STAT = "companies_by_form_status_region";
    /** Events per capture, previous capture, gap and event type. */
    public static final String EVENTS_STAT = "events_per_capture";
    /** Addresses with many active companies (value feature, PLAN.md section 10). */
    public static final String MASS_ADDRESSES = "mass_registration_addresses";

    private ServingNames() {}
}
