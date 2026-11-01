package lvregister.serving;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Read side of the serving store, used by the API. */
public interface ServingQueries {

    /**
     * Companies whose name matches {@code query} (case and diacritics ignored, small typos
     * tolerated), or whose registration number equals it. Best matches first.
     */
    Page<SearchHit> search(String query, int page, int size);

    Optional<CompanyView> company(String regNumber);

    /** The companies that exist among {@code regNumbers}, in no particular order. */
    List<CompanyView> companies(Collection<String> regNumbers);

    /**
     * The roles that the given parties ({@link PartyLink#personKey}, {@link PartyLink#companyKey})
     * hold, at most {@code limit}, ordered by party key and registration number. Joint owners
     * count with each owner; persons without a key and foreign entities have no links.
     */
    List<PartyLink> rolesOfParties(Collection<String> partyKeys, int limit);

    /** Events of one company, newest capture first. */
    Page<EventView> eventsOf(String regNumber, int page, int size);

    /** All events, newest capture first, optionally of one capture date and one type. */
    Page<EventView> events(Optional<LocalDate> captureDate, Optional<String> type, int page, int size);

    /** Runs, newest capture first. */
    Page<RunView> runs(int page, int size);

    Optional<RunView> run(String runId);

    /** All rows of one stat, ordered by key. */
    List<StatView> stats(String stat);

    /** Results of one feature, best rank first. */
    Page<FeatureView> feature(String feature, int page, int size);
}
