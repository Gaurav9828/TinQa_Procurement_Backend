package com.tinqa.procurement.common.monitoring;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param enabled                  off: requests are not measured and the data source is not wrapped
 * @param maxQueriesPerRequest     a request running more SQL statements than this is logged, and fails the strict
 *                                 quality gate in tests
 * @param maxRepeatsOfOneStatement the same statement this many times in one request is an N+1 query (one query per
 *                                 row of an earlier result)
 * @param slowRequest              requests slower than this are logged
 * @param reportFile               per-endpoint statistics are written here when the application stops; the test suite
 *                                 sets it for the quality gate
 */
@ConfigurationProperties(prefix = "monitoring.api")
public record ApiMonitoringProperties(@DefaultValue("true") boolean enabled,
                                      @DefaultValue("25") int maxQueriesPerRequest,
                                      @DefaultValue("5") int maxRepeatsOfOneStatement,
                                      @DefaultValue("1s") Duration slowRequest,
                                      @DefaultValue("") String reportFile) {
}
