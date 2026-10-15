package com.tinqa.procurement.productapproval.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param allowSelfDecision maker-checker: false = the Admin L2 who submitted a product cannot also decide it
 */
@ConfigurationProperties(prefix = "tinqa.product-approval")
public record ProductApprovalProperties(@DefaultValue("false") boolean allowSelfDecision) {
}
