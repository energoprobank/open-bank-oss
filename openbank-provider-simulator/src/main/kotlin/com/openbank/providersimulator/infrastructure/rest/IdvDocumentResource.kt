// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator.infrastructure.rest

import com.fasterxml.jackson.annotation.JsonProperty
import com.openbank.providersimulator.application.IdvDocumentDesk
import com.openbank.providersimulator.domain.Applicant
import com.openbank.providersimulator.domain.DocumentFacts
import com.openbank.providersimulator.domain.DocumentType
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.Locale

/** The IDV vendor's document steps: the photo of the document (OCR + MRZ), then its NFC chip. */
@Path("/api/v1/idv/documents")
@Tag(name = "Identity verification and liveness (simulated)")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
class IdvDocumentResource {
    @Inject
    lateinit var desk: IdvDocumentDesk

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Read a document: OCR of the data page and the MRZ (what the document says)")
    fun scan(body: DocumentScanRequest?): Response {
        val request = requireNotNull(body) { "request body is required" }
        val applicant = requireNotNull(request.applicant) { "applicant is required" }
        val typeName = requireNotNull(request.documentType) { "document_type is required" }
            .trim().uppercase(Locale.ROOT)
        val type = requireNotNull(DocumentType.entries.firstOrNull { it.name == typeName }) {
            "document_type must be one of ${DocumentType.entries.joinToString { it.name }}"
        }
        val doc = desk.scan(
            requireNotNull(request.reference) { "reference is required" },
            type,
            request.issuingCountry ?: "CZ",
            Applicant(
                applicant.givenName.orEmpty(),
                applicant.familyName.orEmpty(),
                applicant.dateOfBirth,
                applicant.nationalId,
            ),
        )
        val fields = DocumentFieldsView.of(doc.facts)
        val mrz = linkedMapOf<String, Any?>("check_digits_valid" to true) + fields.asMap()
        val view = mapOf("id" to doc.id, "ocr" to fields, "mrz" to mrz, "chip_present" to true)
        return Response.status(Response.Status.CREATED).entity(view).build()
    }

    @POST
    @Path("/{id}/chip")
    @Operation(summary = "Read the document's chip: DG1 and passive authentication of the SOD")
    fun chip(@PathParam("id") id: String): Map<String, Any?> {
        val (doc, reading) = desk.readChip(id) ?: throw NotFoundException()
        return mapOf(
            "dg1" to DocumentFieldsView.of(reading.dg1),
            "passive_authentication" to mapOf(
                "status" to if (reading.passiveAuthenticationValid) "VALID" else "INVALID",
                "signer" to reading.signer,
                "detail" to reading.detail,
            ),
            "photo_ref" to "chip-photo:${doc.id}",
        )
    }
}

data class DocumentScanRequest(
    val reference: String? = null,
    @JsonProperty("document_type") val documentType: String? = null,
    @JsonProperty("issuing_country") val issuingCountry: String? = null,
    val applicant: ApplicantDto? = null,
)

/** The fields a document carries, the same in its OCR zone, its MRZ and its chip's DG1. */
data class DocumentFieldsView(
    @JsonProperty("given_name") val givenName: String,
    @JsonProperty("family_name") val familyName: String,
    @JsonProperty("date_of_birth") val dateOfBirth: String?,
    val sex: String?,
    @JsonProperty("national_id") val nationalId: String?,
    @JsonProperty("document_number") val documentNumber: String,
    @JsonProperty("issuing_country") val issuingCountry: String,
    val nationality: String,
    @JsonProperty("expiry_date") val expiryDate: String,
) {
    fun asMap(): Map<String, Any?> = linkedMapOf(
        "given_name" to givenName,
        "family_name" to familyName,
        "date_of_birth" to dateOfBirth,
        "sex" to sex,
        "national_id" to nationalId,
        "document_number" to documentNumber,
        "issuing_country" to issuingCountry,
        "nationality" to nationality,
        "expiry_date" to expiryDate,
    )

    companion object {
        fun of(f: DocumentFacts) = DocumentFieldsView(
            f.givenName,
            f.familyName,
            f.dateOfBirth?.toString(),
            f.sex?.code,
            f.nationalId,
            f.documentNumber,
            f.issuingCountry,
            f.nationality,
            f.expiryDate.toString(),
        )
    }
}
