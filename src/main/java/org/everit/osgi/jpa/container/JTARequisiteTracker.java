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

import javax.transaction.TransactionManager;

import org.apache.aries.jpa.container.parsing.ParsedPersistenceUnit;
import org.everit.osgi.util.core.requisite.AbstractRequisiteTracker;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;

public class JTARequisiteTracker extends AbstractRequisiteTracker<ParsedPersistenceUnit> {

    public JTARequisiteTracker(final BundleContext context,
            final PersistenceBundleManager persistenceBundleManager) throws InvalidSyntaxException {
        super(context, "(" + Constants.OBJECTCLASS + "=" + TransactionManager.class.getName() + ")");
    }

    @Override
    protected Comparable<ServiceReference> createComparableFromReference(
            final ServiceReference reference) {
        return new Comparable<ServiceReference>() {

            @Override
            public int compareTo(final ServiceReference o) {
                return reference.compareTo(o);
            }
        };
    }

    @Override
    protected boolean isReferenceSuitable(final ParsedPersistenceUnit component, final ServiceReference requirement) {
        return true;
    }

}
