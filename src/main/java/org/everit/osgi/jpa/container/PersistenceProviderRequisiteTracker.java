package org.everit.osgi.jpa.container;

/*
 * Copyright (c) 2011, Everit Kft.
 *
 * All rights reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301  USA
 */

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.spi.PersistenceProvider;

import org.apache.aries.jpa.container.impl.InvalidRangeCombination;
import org.apache.aries.jpa.container.parsing.ParsedPersistenceUnit;
import org.apache.aries.util.VersionRange;
import org.everit.osgi.util.core.requisite.AbstractRequisiteTracker;
import org.everit.osgi.util.core.requisite.RequisiteListener;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.Version;

class PersistenceProviderRequisiteTracker extends
        AbstractRequisiteTracker<BundleWithParsedPersistenceUnits> {

    /** Logger */
    private static final Logger LOGGER = LoggerFactory.getLogger("org.apache.aries.jpa.container");

    private Map<Bundle, ProviderNameWithVersionRange> providerNamesByBundles =
            new ConcurrentHashMap<Bundle, ProviderNameWithVersionRange>();

    String defaultProviderClassName;

    public PersistenceProviderRequisiteTracker(final BundleContext context, final String defaultProviderName)
            throws InvalidSyntaxException {
        super(context, "(" + Constants.OBJECTCLASS + "=" + PersistenceProvider.class.getName() + ")");
        defaultProviderClassName = defaultProviderName;
    }

    @Override
    public void addDependentObject(final BundleWithParsedPersistenceUnits dependentObject,
            final RequisiteListener<BundleWithParsedPersistenceUnits> requisiteListener) {
        if (providerNamesByBundles.containsKey(dependentObject.getBundle())) {
            return;
        }
        ProviderNameWithVersionRange providerNameWithVersion = getProviderName(dependentObject
                .getParsedPersistenceUnits());
        if (providerNameWithVersion != null) {
            providerNamesByBundles.put(dependentObject.getBundle(), providerNameWithVersion);
            super.addDependentObject(dependentObject, requisiteListener);
        }
    }

    /**
     * Turn a Collection of version ranges into a single range including common overlap
     * 
     * @param versionRanges
     * @return
     * @throws InvalidRangeCombination
     */
    private VersionRange combineVersionRanges(final List<VersionRange> versionRanges) throws InvalidRangeCombination {

        Version minVersion = new Version(0, 0, 0);
        Version maxVersion = null;
        boolean minExclusive = false;
        boolean maxExclusive = false;

        for (VersionRange range : versionRanges) {
            int minComparison = minVersion.compareTo(range.getMinimumVersion());
            // If minVersion is smaller then we have a new, larger, minimum
            if (minComparison < 0) {
                minVersion = range.getMinimumVersion();
                minExclusive = range.isMinimumExclusive();
            }
            // Only update if it is the same version but more restrictive
            else if ((minComparison == 0) && range.isMaximumExclusive()) {
                minExclusive = true;
            }

            if (range.isMaximumUnbounded()) {
                continue;
            } else if (maxVersion == null) {
                maxVersion = range.getMaximumVersion();
                maxExclusive = range.isMaximumExclusive();
            } else {
                int maxComparison = maxVersion.compareTo(range.getMaximumVersion());

                // We have a new, lower maximum
                if (maxComparison > 0) {
                    maxVersion = range.getMaximumVersion();
                    maxExclusive = range.isMaximumExclusive();
                    // If the maximum is the same then make sure we set the exclusivity
                    // properly
                } else if ((maxComparison == 0) && range.isMaximumExclusive()) {
                    maxExclusive = true;
                }
            }
        }

        // Now check that we have valid values
        int check = (maxVersion == null) ? -1 : minVersion.compareTo(maxVersion);
        // If min is greater than max, or min is equal to max and one of the
        // exclusive
        // flags is set then we have a problem!
        if ((check > 0) || ((check == 0) && (minExclusive || maxExclusive))) {
            throw new InvalidRangeCombination(minVersion, minExclusive, maxVersion, maxExclusive);
        }

        // Turn the Versions into a version range string
        StringBuilder rangeString = new StringBuilder();
        rangeString.append(minVersion);

        if (maxVersion != null) {
            rangeString.insert(0, minExclusive ? "(" : "[");
            rangeString.append(",");
            rangeString.append(maxVersion);
            rangeString.append(maxExclusive ? ")" : "]");
        }
        // Turn that string back into a VersionRange
        return VersionRange.parseVersionRange(rangeString.toString());
    }

    @Override
    protected Comparable<ServiceReference> createComparableFromReference(
            final ServiceReference reference) {
        Object property = reference.getProperty("javax.persistence.provider");
        if (property == null) {
            LOGGER.warn("javax.persistence.provider property is missing from the service registration: " + reference);
            return null;
        }
        final String providerName = property.toString();
        return new Comparable<ServiceReference>() {

            @Override
            public int compareTo(final ServiceReference o) {
                Object tmpProviderNameObject = o.getProperty("javax.persistence.provider");
                if (tmpProviderNameObject == null) {
                    return -1;
                } else {
                    return providerName.compareTo(tmpProviderNameObject.toString());
                }
            }
        };
    }

    /**
     * Get a persistence provider names with versions.
     * 
     * @param parsedPersistenceUnits
     *            The parsed persistence units that the provider names with versions calculated from.
     * @return A providerNameWithVersion where version range is null if it does not matter, providerName is "*" if it
     *         does not matter and the result is null if there was an error and it was logged.
     */
    private ProviderNameWithVersionRange getProviderName(
            final Collection<ParsedPersistenceUnit> parsedPersistenceUnits) {
        Set<String> ppClassNames = new HashSet<String>();
        List<VersionRange> versionRanges = new ArrayList<VersionRange>();
        // Fill the set of class names and version Filters
        for (ParsedPersistenceUnit unit : parsedPersistenceUnits) {
            Map<String, Object> metadata = unit.getPersistenceXmlMetadata();
            String provider = (String) metadata.get(ParsedPersistenceUnit.PROVIDER_CLASSNAME);
            // get providers specified in the persistence units
            if ((provider != null) && !!!provider.equals("")) {
                ppClassNames.add(provider);

                Properties props = (Properties) metadata.get(ParsedPersistenceUnit.PROPERTIES);

                if ((props != null) && props.containsKey(ParsedPersistenceUnit.JPA_PROVIDER_VERSION)) {

                    String versionRangeString = props.getProperty(ParsedPersistenceUnit.JPA_PROVIDER_VERSION, "0.0.0");
                    try {
                        versionRanges.add(VersionRange.parseVersionRange(versionRangeString));
                    } catch (IllegalArgumentException e) {
                        LOGGER.error("Error getting version range for persistence units", e);
                    }
                }
            }
        }
        // If we have too many provider class names or incompatible version ranges
        // specified then blow up

        VersionRange range = null;
        if (!!!versionRanges.isEmpty()) {
            try {
                range = combineVersionRanges(versionRanges);
            } catch (InvalidRangeCombination e) {
                Bundle bundle = parsedPersistenceUnits.iterator().next().getDefiningBundle();
                LOGGER.error("Error getting version range for persistence units", e);
                return null;
            }
        }

        ProviderNameWithVersionRange result = null;
        if (ppClassNames.size() > 1) {
            Bundle bundle = parsedPersistenceUnits.iterator().next().getDefiningBundle();
            LOGGER.error("Multiple provider usage for one persistence bundle is not supported: "
                    + bundle.getSymbolicName() + " [" + ppClassNames + "]");
            return null;
        } else if (ppClassNames.size() < 1) {
            if (defaultProviderClassName == null) {
                result = new ProviderNameWithVersionRange("*", range);
            } else {
                result = new ProviderNameWithVersionRange(defaultProviderClassName, range);
            }
        } else {
            result = new ProviderNameWithVersionRange(ppClassNames.iterator().next(), range);
        }
        return result;
    }

    @Override
    protected boolean isReferenceSuitable(final BundleWithParsedPersistenceUnits dependentObject,
            final ServiceReference reference) {
        ProviderNameWithVersionRange providerNameWithVersionRange = providerNamesByBundles.get(dependentObject
                .getBundle());

        if (providerNameWithVersionRange == null) {
            return false;
        }

        String providerClass = providerNameWithVersionRange.getClassName();
        if (!"*".equals(providerClass) && !providerClass.equals(reference.getProperty("javax.persistence.provider"))) {
            return false;
        }

        VersionRange matchingCriteria = providerNameWithVersionRange.getVersionRange();
        if ((matchingCriteria == null) || matchingCriteria.matches(reference.getBundle().getVersion())) {
            return true;
        }
        return false;
    }

    @Override
    public void removeDependentObject(final BundleWithParsedPersistenceUnits dependentObject) {
        if (providerNamesByBundles.remove(dependentObject.getBundle()) != null) {
            super.removeDependentObject(dependentObject);
        }
    }
}
