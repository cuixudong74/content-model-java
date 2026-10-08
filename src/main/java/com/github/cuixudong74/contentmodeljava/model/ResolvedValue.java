package com.github.cuixudong74.contentmodeljava.model;

/**
 * Result of resolving a single field value for one requested language.
 *
 * @param value         the resolved value (null only when {@code state} is UNSET)
 * @param state         how the value was obtained
 * @param sourceLang    actual language the value comes from (null for non-localized / default / unset)
 */
public record ResolvedValue(Object value, ValueState state, String sourceLang) {

    public enum ValueState {
        /** Value present in the requested language. */
        PRESENT,
        /** Value present but obtained via language fallback. */
        FALLBACK,
        /** Field missing, projected from its declared default value. */
        DEFAULT,
        /** Optional field with no value and no default. */
        UNSET
    }
}
