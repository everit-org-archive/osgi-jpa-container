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
import java.util.Collection;
import java.util.Iterator;

import org.apache.aries.jpa.container.parsing.ParsedPersistenceUnit;
import org.osgi.framework.Bundle;

/**
 * Simple immutable pojo that holds a bundle and the belonging parsed persistence units. The equals and hashcode
 * functions are overridden to have equality in case the bundle is the same.
 */
public class BundleWithParsedPersistenceUnits {

    /**
     * The bundle that the parsed persistence units belong to.
     */
    private Bundle bundle;

    /**
     * The parsed persistence units that belong to the bundle.
     */
    private Collection<ParsedPersistenceUnit> parsedPersistenceUnits;

    /**
     * Simple constructor setting all the fields.
     * 
     * @param bundle
     *            The bundle that the parsed persistence units belong to.
     * @param parsedPersistenceUnits
     *            The parsed persistence units that belong to the bundle.
     */
    public BundleWithParsedPersistenceUnits(final Bundle bundle,
            final Collection<ParsedPersistenceUnit> parsedPersistenceUnits) {
        this.bundle = bundle;
        this.parsedPersistenceUnits = parsedPersistenceUnits;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        BundleWithParsedPersistenceUnits other = (BundleWithParsedPersistenceUnits) obj;
        if (bundle == null) {
            if (other.bundle != null) {
                return false;
            }
        } else if (!bundle.equals(other.bundle)) {
            return false;
        }
        return true;
    }

    public Bundle getBundle() {
        return bundle;
    }

    public Collection<ParsedPersistenceUnit> getParsedPersistenceUnits() {
        return parsedPersistenceUnits;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = (prime * result) + ((bundle == null) ? 0 : bundle.hashCode());
        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Bundle: '").append(bundle.getSymbolicName()).append(":")
                .append(bundle.getVersion().toString()).append("' Persistence units:");
        Iterator<ParsedPersistenceUnit> iterator = parsedPersistenceUnits.iterator();
        
        while (iterator.hasNext()) {
            ParsedPersistenceUnit ppu = iterator.next();
            sb.append(ppu.getPersistenceXmlMetadata().get(ParsedPersistenceUnit.UNIT_NAME));
            if (iterator.hasNext()) {
                sb.append(", ");
            }
        }
        return sb.toString();
    }

}
