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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.apache.aries.jpa.container.parsing.ParsedPersistenceUnit;
import org.everit.osgi.util.core.requisite.AbstractRequisiteTracker;
import org.everit.osgi.util.core.requisite.RequisiteListener;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;

class DataSourceRequisiteTracker extends AbstractRequisiteTracker<ParsedPersistenceUnit> {

    private static final Logger LOGGER = LoggerFactory.getLogger(DataSourceRequisiteTracker.class);

    public static final String OSGI_DS_SERVICE_PATTERN = "osgi\\:service/javax\\.sql\\.DataSource/?(.*)?";
    private final boolean jta;

    private final Pattern dataSourceServiceMatcher = Pattern.compile(OSGI_DS_SERVICE_PATTERN);
    private Map<ParsedPersistenceUnit, Filter> dataSourceFiltersByPPUs =
            new ConcurrentHashMap<ParsedPersistenceUnit, Filter>();

    public DataSourceRequisiteTracker(BundleContext context, boolean jta) throws InvalidSyntaxException {
        super(context, "(" + Constants.OBJECTCLASS + "=" + DataSource.class.getName() + ")");
        this.jta = jta;
    }

    @Override
    public void addDependentObject(ParsedPersistenceUnit dependentObject,
            RequisiteListener<ParsedPersistenceUnit> requisiteListener) {

        String dataSourceServiceExpression = null;
        if (jta) {
            dataSourceServiceExpression = (String) dependentObject.getPersistenceXmlMetadata().get(
                    ParsedPersistenceUnit.JTA_DATASOURCE);
        } else {
            dataSourceServiceExpression = (String) dependentObject.getPersistenceXmlMetadata().get(
                    ParsedPersistenceUnit.NON_JTA_DATASOURCE);
        }

        Matcher matcher = dataSourceServiceMatcher.matcher(dataSourceServiceExpression);
        if (!matcher.matches()) {
            if (jta) {
                LOGGER.error("In case of OSGI service jta-data-source has to match the following regex: "
                        + OSGI_DS_SERVICE_PATTERN + ". Current value is: " + dataSourceServiceExpression);
            } else {
                LOGGER.error("In case of OSGI service non-jta-data-source has to match the following regex: "
                        + OSGI_DS_SERVICE_PATTERN + ". Current value is: " + dataSourceServiceExpression);
            }
        } else {

            int groupCount = matcher.groupCount();

            String filterExpression = null;
            if (groupCount < 1) {
                LOGGER.warn("All Datasource will be accepted as no filter is provided in the datasource expression: "
                        + dataSourceServiceExpression);
                filterExpression = "(" + Constants.OBJECTCLASS + "=" + DataSource.class.getName() + ")";
            } else {
                filterExpression = matcher.group(1);
            }
            BundleContext bcx = dependentObject.getDefiningBundle().getBundleContext();
            try {
                Filter filter = bcx.createFilter(filterExpression);
                dataSourceFiltersByPPUs.put(dependentObject, filter);
            } catch (InvalidSyntaxException e) {
                LOGGER.error("Invalid syntax at filter part in dataSource expression: " + filterExpression,
                        e);
            }
        }

        super.addDependentObject(dependentObject, requisiteListener);
    }

    @Override
    protected Comparable<ServiceReference> createComparableFromReference(ServiceReference reference) {
        return new Comparable<ServiceReference>() {
            
            @Override
            public int compareTo(ServiceReference o) { 
                return 0;
            }
        };
    }

    @Override
    protected boolean isReferenceSuitable(ParsedPersistenceUnit dependentObject, ServiceReference reference) {
        Filter filter = dataSourceFiltersByPPUs.get(dependentObject);
        if (filter == null) {
            return true;
        }
        return filter.match(reference);
    }

    @Override
    public void removeDependentObject(ParsedPersistenceUnit dependentObject) {
        super.removeDependentObject(dependentObject);
        dataSourceFiltersByPPUs.remove(dependentObject);
    }

}
