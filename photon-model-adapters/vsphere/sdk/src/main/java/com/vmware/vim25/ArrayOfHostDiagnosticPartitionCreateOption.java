
package com.vmware.vim25;

import java.util.ArrayList;
import java.util.List;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlType;


/**
 * <p>Java class for ArrayOfHostDiagnosticPartitionCreateOption complex type.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="ArrayOfHostDiagnosticPartitionCreateOption"&gt;
 *   &lt;complexContent&gt;
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType"&gt;
 *       &lt;sequence&gt;
 *         &lt;element name="HostDiagnosticPartitionCreateOption" type="{urn:vim25}HostDiagnosticPartitionCreateOption" maxOccurs="unbounded" minOccurs="0"/&gt;
 *       &lt;/sequence&gt;
 *     &lt;/restriction&gt;
 *   &lt;/complexContent&gt;
 * &lt;/complexType&gt;
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "ArrayOfHostDiagnosticPartitionCreateOption", propOrder = {
    "hostDiagnosticPartitionCreateOption"
})
public class ArrayOfHostDiagnosticPartitionCreateOption {

    @XmlElement(name = "HostDiagnosticPartitionCreateOption")
    protected List<HostDiagnosticPartitionCreateOption> hostDiagnosticPartitionCreateOption;

    /**
     * Gets the value of the hostDiagnosticPartitionCreateOption property.
     * 
     * <p>
     * This accessor method returns a reference to the live list,
     * not a snapshot. Therefore any modification you make to the
     * returned list will be present inside the JAXB object.
     * This is why there is not a <CODE>set</CODE> method for the hostDiagnosticPartitionCreateOption property.
     * 
     * <p>
     * For example, to add a new item, do as follows:
     * <pre>
     *    getHostDiagnosticPartitionCreateOption().add(newItem);
     * </pre>
     * 
     * 
     * <p>
     * Objects of the following type(s) are allowed in the list
     * {@link HostDiagnosticPartitionCreateOption }
     * 
     * 
     */
    public List<HostDiagnosticPartitionCreateOption> getHostDiagnosticPartitionCreateOption() {
        if (hostDiagnosticPartitionCreateOption == null) {
            hostDiagnosticPartitionCreateOption = new ArrayList<HostDiagnosticPartitionCreateOption>();
        }
        return this.hostDiagnosticPartitionCreateOption;
    }

}
