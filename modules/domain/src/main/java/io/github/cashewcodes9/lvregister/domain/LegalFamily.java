package io.github.cashewcodes9.lvregister.domain;

/** North-Data-style family of a legal form. The mapping from UR codes lives in `normalize`. */
public enum LegalFamily {
    LLC,
    CORPORATION,
    SOLE_PROPRIETORSHIP,
    GENERAL_PARTNERSHIP,
    LIMITED_PARTNERSHIP,
    COOPERATIVE,
    ASSOCIATION,
    FOUNDATION,
    BRANCH,
    REPRESENTATION,
    PUBLIC_ENTERPRISE,
    RELIGIOUS_ORGANISATION,
    POLITICAL_ORGANISATION,
    OTHER
}
