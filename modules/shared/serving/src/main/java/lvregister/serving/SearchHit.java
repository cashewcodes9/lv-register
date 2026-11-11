package lvregister.serving;

/** A company found by name or registration number; higher score = better match. */
public record SearchHit(String regNumber, String name, String legalFormCode, String status, double score) {}
