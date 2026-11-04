package com.tinqa.procurement.security.access.role;

import com.tinqa.procurement.security.access.PermissionGroup;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * A designation kept in the database (e.g. "Marketing Executive"): its permission groups are chosen by an admin from
 * the groups they hold themselves. An employee holds either a built-in {@link com.tinqa.procurement.security.access.Designation}
 * or one of these. SELF_SERVICE is added on top and never stored.
 */
@Entity
@Table(name = "custom_role")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Generated from the label when the role is created; never changes
    @Column(nullable = false, length = 60, updatable = false)
    private String code;

    @Column(nullable = false, length = 100)
    private String label;

    @Column(nullable = false, length = 100)
    private String department;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    private boolean active;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "custom_role_group", joinColumns = @JoinColumn(name = "role_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "permission_group", nullable = false, length = 60)
    @Builder.Default
    private Set<PermissionGroup> groups = EnumSet.noneOf(PermissionGroup.class);

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
