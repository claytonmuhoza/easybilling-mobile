package com.claytonmuhoza.easybilling.domain.configuration

/**
 * A text field of the tax profile, with its rules.
 *
 * The sizes come from the parameter table of the OBR specification. Two of them differ in the
 * error messages of the same document; the smaller one is applied (see `docs/architecture.md`):
 * `tp_TIN` (30 in the table, 15 in the message) and `tp_activity_sector` (250 and 20).
 *
 * Province and avenue are optional for the OBR but required here (internal rule).
 *
 * @property obrName name of the parameter in the OBR specification.
 * @property maxLength longest accepted value, in characters, after trimming.
 * @property required whether the value may stay empty.
 */
enum class TaxProfileField(val obrName: String, val maxLength: Int, val required: Boolean) {
    /** Trade name, or first and last names of a natural person. */
    NAME("tp_name", 100, true),

    /** Tax identification number (NIF). */
    TIN("tp_TIN", 15, true),

    /** Trade register number. */
    TRADE_NUMBER("tp_trade_number", 20, true),

    /** Post office box. */
    POSTAL_NUMBER("tp_postal_number", 20, false),

    /** Phone number. */
    PHONE_NUMBER("tp_phone_number", 20, true),

    /** Province of the address. */
    PROVINCE("tp_address_province", 50, true),

    /** Commune of the address. */
    COMMUNE("tp_address_commune", 50, true),

    /** Quartier or colline of the address. */
    QUARTIER("tp_address_quartier", 50, true),

    /** Avenue of the address. */
    AVENUE("tp_address_avenue", 50, true),

    /** Street of the address. */
    RUE("tp_address_rue", 50, false),

    /** Number of the address. */
    ADDRESS_NUMBER("tp_address_number", 10, false),

    /** Activity sector. */
    ACTIVITY_SECTOR("tp_activity_sector", 20, true),

    /** Legal form. */
    LEGAL_FORM("tp_legal_form", 50, true),
}

/** A choice of the tax profile that has no default value. */
enum class TaxProfileChoice {
    /** Natural or legal person. */
    TAXPAYER_TYPE,

    /** Tax office of the taxpayer. */
    FISCAL_CENTER,
}

/** Why the value of a text field is refused. */
sealed interface FieldProblem {
    /** A required value is empty. */
    data object Missing : FieldProblem

    /** The value is longer than [maxLength] characters. */
    data class TooLong(val maxLength: Int) : FieldProblem
}

/** Result of [TaxProfileForm.check]. */
sealed interface TaxProfileCheck {
    /** Every rule holds. */
    data class Valid(val profile: TaxProfile) : TaxProfileCheck

    /**
     * Some rules are broken; every problem is reported at once.
     *
     * @property problems the refused text fields.
     * @property missingChoices the choices not made.
     */
    data class Invalid(
        val problems: Map<TaxProfileField, FieldProblem>,
        val missingChoices: Set<TaxProfileChoice>,
    ) : TaxProfileCheck
}

/**
 * The tax profile as typed by the user, not checked yet.
 *
 * @property taxpayerType the chosen kind of taxpayer, or null.
 * @property texts the typed texts; a missing field counts as empty.
 * @property taxes the taxes the business is registered for.
 * @property fiscalCenter the chosen tax office, or null.
 */
data class TaxProfileForm(
    val taxpayerType: TaxpayerType? = null,
    val texts: Map<TaxProfileField, String> = emptyMap(),
    val taxes: TaxRegistrations = TaxRegistrations(),
    val fiscalCenter: FiscalCenter? = null,
) {
    /**
     * @param field a text field.
     * @param value its new content.
     * @return this form with [field] set to [value].
     */
    fun with(field: TaxProfileField, value: String): TaxProfileForm = copy(texts = texts + (field to value))

    /**
     * Applies every rule of the tax profile. Texts are trimmed first.
     *
     * @return the profile, or every broken rule.
     */
    fun check(): TaxProfileCheck {
        val values = TaxProfileField.entries.associateWith { texts[it].orEmpty().trim() }
        val problems = values.mapNotNull { (field, value) -> problemOf(field, value)?.let { field to it } }.toMap()
        val type = taxpayerType
        val center = fiscalCenter
        if (problems.isNotEmpty() || type == null || center == null) {
            val missingChoices = buildSet {
                if (type == null) add(TaxProfileChoice.TAXPAYER_TYPE)
                if (center == null) add(TaxProfileChoice.FISCAL_CENTER)
            }
            return TaxProfileCheck.Invalid(problems, missingChoices)
        }
        return TaxProfileCheck.Valid(profile(type, center, values::getValue))
    }

    private fun problemOf(field: TaxProfileField, value: String): FieldProblem? = when {
        value.isEmpty() && field.required -> FieldProblem.Missing
        value.length > field.maxLength -> FieldProblem.TooLong(field.maxLength)
        else -> null
    }

    private fun profile(type: TaxpayerType, center: FiscalCenter, text: (TaxProfileField) -> String) =
        TaxProfile(
            taxpayerType = type,
            name = text(TaxProfileField.NAME),
            tin = text(TaxProfileField.TIN),
            tradeNumber = text(TaxProfileField.TRADE_NUMBER),
            postalNumber = text(TaxProfileField.POSTAL_NUMBER),
            phoneNumber = text(TaxProfileField.PHONE_NUMBER),
            address = Address(
                province = text(TaxProfileField.PROVINCE),
                commune = text(TaxProfileField.COMMUNE),
                quartier = text(TaxProfileField.QUARTIER),
                avenue = text(TaxProfileField.AVENUE),
                rue = text(TaxProfileField.RUE),
                number = text(TaxProfileField.ADDRESS_NUMBER),
            ),
            taxes = taxes,
            fiscalCenter = center,
            activitySector = text(TaxProfileField.ACTIVITY_SECTOR),
            legalForm = text(TaxProfileField.LEGAL_FORM),
        )

    /** Ways to create a form. */
    companion object {
        /**
         * @param profile a stored profile.
         * @return the form showing [profile], ready to be changed.
         */
        fun from(profile: TaxProfile): TaxProfileForm = TaxProfileForm(
            taxpayerType = profile.taxpayerType,
            texts = mapOf(
                TaxProfileField.NAME to profile.name,
                TaxProfileField.TIN to profile.tin,
                TaxProfileField.TRADE_NUMBER to profile.tradeNumber,
                TaxProfileField.POSTAL_NUMBER to profile.postalNumber,
                TaxProfileField.PHONE_NUMBER to profile.phoneNumber,
                TaxProfileField.PROVINCE to profile.address.province,
                TaxProfileField.COMMUNE to profile.address.commune,
                TaxProfileField.QUARTIER to profile.address.quartier,
                TaxProfileField.AVENUE to profile.address.avenue,
                TaxProfileField.RUE to profile.address.rue,
                TaxProfileField.ADDRESS_NUMBER to profile.address.number,
                TaxProfileField.ACTIVITY_SECTOR to profile.activitySector,
                TaxProfileField.LEGAL_FORM to profile.legalForm,
            ),
            taxes = profile.taxes,
            fiscalCenter = profile.fiscalCenter,
        )
    }
}
