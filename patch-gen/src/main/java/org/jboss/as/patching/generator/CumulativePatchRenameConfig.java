package org.jboss.as.patching.generator;

/**
 * Used if we want to rename the name/versions of a cumulative patch. For example
 * to support patch streams which are independent of the target product.
 *
 * @author <a href="mailto:kabir.khan@jboss.com">Kabir Khan</a>
 */
public interface CumulativePatchRenameConfig {
    /**
     * The renamed name of the product/patch stream
     *
     * @return the new name
     */
    String getAppliesToName();

    /**
     * The renamed applies to version of the product/patch stream
     *
     * @return the new to version
     */
    String getAppliesToVersion();

    /**
     * The renamed resulting version of the product/patch stream
     *
     * @return the new resulting version
     */

    String getResultingVersion();

}
