// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.libs.domain.error.ResourceNotFoundException
import com.openbank.onboarding.application.port.out.BankIdPort
import com.openbank.onboarding.application.port.out.ContactCodeDeliveryPort
import com.openbank.onboarding.application.port.out.EnergoProCustomerPort
import com.openbank.onboarding.application.port.out.EnergoProRecord
import com.openbank.onboarding.application.port.out.IdentityVerificationPort
import com.openbank.onboarding.application.port.out.PartyFacts
import com.openbank.onboarding.application.port.out.PartyMasterDataPort
import com.openbank.onboarding.application.port.out.PersonalDetailsWrite
import com.openbank.onboarding.application.port.out.ProviderApplicant
import com.openbank.onboarding.application.port.out.ProviderCheck
import com.openbank.onboarding.application.port.out.ProviderResult
import com.openbank.onboarding.application.port.out.TaxResidencyWrite
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

// ---------------------------------------------------------------- wire types (provider simulator API)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EnergoProCustomerDto(
    @JsonProperty("customer_number") val customerNumber: String = "",
    val market: String = "",
    @JsonProperty("first_name") val firstName: String = "",
    @JsonProperty("last_name") val lastName: String = "",
    val street: String = "",
    val city: String = "",
    @JsonProperty("postal_code") val postalCode: String = "",
    val email: String = "",
    val phone: String = "",
)

data class MessageDto(val channel: String, val recipient: String, val text: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ApplicantDto(
    @JsonProperty("given_name") val givenName: String? = null,
    @JsonProperty("family_name") val familyName: String? = null,
    @JsonProperty("date_of_birth") val dateOfBirth: String? = null,
    @JsonProperty("national_id") val nationalId: String? = null,
)

data class VerificationRequestDto(
    val kind: String?,
    val reference: String,
    val applicant: ApplicantDto,
    @JsonProperty("document_type") val documentType: String?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CheckDto(val type: String = "", val status: String = "")

@JsonIgnoreProperties(ignoreUnknown = true)
data class VerificationDto(
    val id: String = "",
    val status: String = "",
    val checks: List<CheckDto> = emptyList(),
    val identity: ApplicantDto? = null,
)

// ---------------------------------------------------------------- REST clients, one per provider

@RegisterRestClient(configKey = "energo-pro")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/energo-pro")
@Produces(MediaType.APPLICATION_JSON)
interface EnergoProClient {
    @GET
    @Path("/customers/{number}")
    fun customer(@PathParam("number") number: String): EnergoProCustomerDto
}

@RegisterRestClient(configKey = "otp-gateway")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/otp")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface OtpGatewayClient {
    @POST
    @Path("/messages")
    fun send(message: MessageDto)
}

@RegisterRestClient(configKey = "bank-id")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/bankid")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface BankIdClient {
    @POST
    @Path("/sessions")
    fun start(request: VerificationRequestDto): VerificationDto

    @GET
    @Path("/sessions/{id}")
    fun get(@PathParam("id") id: String): VerificationDto
}

@RegisterRestClient(configKey = "idv")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/idv")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface IdvClient {
    @POST
    @Path("/verifications")
    fun start(request: VerificationRequestDto): VerificationDto

    @GET
    @Path("/verifications/{id}")
    fun get(@PathParam("id") id: String): VerificationDto
}

// ---------------------------------------------------------------- adapters

private fun VerificationDto.toResult() = ProviderResult(
    providerRef = id,
    status = status,
    checks = checks.map { ProviderCheck(it.type, it.status) },
    identity = identity?.let {
        ProviderApplicant(it.givenName.orEmpty(), it.familyName.orEmpty(), it.dateOfBirth, it.nationalId)
    },
)

private fun ProviderApplicant.toDto() = ApplicantDto(givenName, familyName, dateOfBirth, nationalId)

private fun WebApplicationException.status(): Int = response?.status ?: 0

@ApplicationScoped
class EnergoProCustomerAdapter(@RestClient private val client: EnergoProClient) : EnergoProCustomerPort {
    override fun find(customerNumber: String): EnergoProRecord? = try {
        client.customer(customerNumber.trim()).let {
            EnergoProRecord(
                it.customerNumber, it.market, it.firstName, it.lastName,
                it.street, it.city, it.postalCode, it.email, it.phone,
            )
        }
    } catch (e: WebApplicationException) {
        if (e.status() == HTTP_NOT_FOUND) null else throw e
    }
}

@ApplicationScoped
class ContactCodeDeliveryAdapter(@RestClient private val client: OtpGatewayClient) : ContactCodeDeliveryPort {
    override fun sendEmail(to: String, text: String) = client.send(MessageDto("EMAIL", to, text))

    override fun sendSms(to: String, text: String) = client.send(MessageDto("SMS", to, text))
}

@ApplicationScoped
class BankIdAdapter(@RestClient private val client: BankIdClient) : BankIdPort {
    override fun start(reference: String, applicant: ProviderApplicant) =
        client.start(VerificationRequestDto(null, reference, applicant.toDto(), null)).toResult()

    override fun get(providerRef: String) = client.get(providerRef).toResult()
}

@ApplicationScoped
class IdentityVerificationAdapter(@RestClient private val client: IdvClient) : IdentityVerificationPort {
    override fun startDocumentScan(reference: String, applicant: ProviderApplicant, documentType: String?) =
        client.start(VerificationRequestDto("DOCUMENT_SCAN", reference, applicant.toDto(), documentType)).toResult()

    override fun startLiveness(reference: String, applicant: ProviderApplicant) =
        client.start(VerificationRequestDto("LIVENESS", reference, applicant.toDto(), null)).toResult()

    override fun get(providerRef: String) = client.get(providerRef).toResult()
}

// ---------------------------------------------------------------- party-service (blocking writes)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PartyDto(
    val id: String = "",
    val legalName: String = "",
    val dateOfBirth: String? = null,
    val taxId: String? = null,
    val market: String? = null,
    val status: String = "",
)

data class AddressDto(
    val line1: String,
    val line2: String?,
    val city: String,
    val postalCode: String,
    val countryCode: String,
)

data class OnboardingDetailsDto(
    val dateOfBirth: String,
    val nationalId: String,
    val phone: String,
    val address: AddressDto,
)

data class TaxResidencyDto(val country: String, val tin: String?)

data class TaxSelfCertificationDto(val usPerson: Boolean, val residencies: List<TaxResidencyDto>)

@RegisterRestClient(configKey = "party-service")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/parties")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface PartyMasterDataClient {
    @GET
    @Path("/{id}")
    fun get(@PathParam("id") id: UUID): PartyDto

    @PUT
    @Path("/{id}/onboarding-details")
    fun onboardingDetails(@PathParam("id") id: UUID, body: OnboardingDetailsDto)

    @PUT
    @Path("/{id}/tax-self-certification")
    fun taxSelfCertification(@PathParam("id") id: UUID, body: TaxSelfCertificationDto)
}

/**
 * party-service's answers keep their meaning: 400 stays a validation error (the applicant's input),
 * 404 a missing party, 409 a decided application. Anything else is an outage and surfaces as such.
 */
@ApplicationScoped
class PartyMasterDataAdapter(@RestClient private val client: PartyMasterDataClient) : PartyMasterDataPort {
    override fun get(partyId: UUID): PartyFacts? = try {
        client.get(partyId).let {
            PartyFacts(partyId, it.legalName, it.dateOfBirth, it.taxId, (it.market ?: "CZ").uppercase(), it.status)
        }
    } catch (e: WebApplicationException) {
        if (e.status() == HTTP_NOT_FOUND) null else throw e
    }

    override fun writePersonalDetails(partyId: UUID, details: PersonalDetailsWrite) = translate {
        client.onboardingDetails(
            partyId,
            OnboardingDetailsDto(
                details.dateOfBirth,
                details.nationalId,
                details.phone,
                AddressDto(details.street, null, details.city, details.postalCode, details.country),
            ),
        )
    }

    override fun writeTaxSelfCertification(partyId: UUID, usPerson: Boolean, residencies: List<TaxResidencyWrite>) =
        translate {
            client.taxSelfCertification(
                partyId,
                TaxSelfCertificationDto(
                    usPerson,
                    residencies.map {
                        TaxResidencyDto(it.country, it.tin)
                    },
                ),
            )
        }

    /** party-service's own explanation (ApiError `detail` / `message`), e.g. which field is wrong. */
    private fun reason(e: WebApplicationException): String? = runCatching {
        @Suppress("UNCHECKED_CAST")
        val body = e.response.readEntity(Map::class.java) as Map<String, Any?>
        (body["detail"] ?: body["message"])?.toString()?.take(MAX_REASON)
    }.getOrNull()

    private fun translate(call: () -> Unit) {
        try {
            call()
        } catch (e: WebApplicationException) {
            throw when (e.status()) {
                HTTP_BAD_REQUEST -> IllegalArgumentException(reason(e) ?: "The bank could not accept these details")
                HTTP_NOT_FOUND -> ResourceNotFoundException("No such applicant", "APPLICATION_NOT_FOUND")
                HTTP_CONFLICT -> ResourceConflictException(
                    "These details can no longer be changed",
                    "APPLICATION_DECIDED",
                )
                else -> e
            }
        }
    }
}

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_NOT_FOUND = 404
private const val HTTP_CONFLICT = 409
private const val MAX_REASON = 200
