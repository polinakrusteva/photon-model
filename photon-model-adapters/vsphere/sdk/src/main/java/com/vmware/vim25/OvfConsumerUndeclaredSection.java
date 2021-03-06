
package com.vmware.vim25;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlType;


/**
 * <p>Java class for OvfConsumerUndeclaredSection complex type.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="OvfConsumerUndeclaredSection"&gt;
 *   &lt;complexContent&gt;
 *     &lt;extension base="{urn:vim25}OvfConsumerCallbackFault"&gt;
 *       &lt;sequence&gt;
 *         &lt;element name="qualifiedSectionType" type="{http://www.w3.org/2001/XMLSchema}string"/&gt;
 *       &lt;/sequence&gt;
 *     &lt;/extension&gt;
 *   &lt;/complexContent&gt;
 * &lt;/complexType&gt;
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "OvfConsumerUndeclaredSection", propOrder = {
    "qualifiedSectionType"
})
public class OvfConsumerUndeclaredSection
    extends OvfConsumerCallbackFault
{

    @XmlElement(required = true)
    protected String qualifiedSectionType;

    /**
     * Gets the value of the qualifiedSectionType property.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    public String getQualifiedSectionType() {
        return qualifiedSectionType;
    }

    /**
     * Sets the value of the qualifiedSectionType property.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    public void setQualifiedSectionType(String value) {
        this.qualifiedSectionType = value;
    }

}
