
package com.vmware.vim25;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlSchemaType;
import javax.xml.bind.annotation.XmlType;
import javax.xml.datatype.XMLGregorianCalendar;


/**
 * <p>Java class for OnceTaskScheduler complex type.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="OnceTaskScheduler"&gt;
 *   &lt;complexContent&gt;
 *     &lt;extension base="{urn:vim25}TaskScheduler"&gt;
 *       &lt;sequence&gt;
 *         &lt;element name="runAt" type="{http://www.w3.org/2001/XMLSchema}dateTime" minOccurs="0"/&gt;
 *       &lt;/sequence&gt;
 *     &lt;/extension&gt;
 *   &lt;/complexContent&gt;
 * &lt;/complexType&gt;
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "OnceTaskScheduler", propOrder = {
    "runAt"
})
public class OnceTaskScheduler
    extends TaskScheduler
{

    @XmlSchemaType(name = "dateTime")
    protected XMLGregorianCalendar runAt;

    /**
     * Gets the value of the runAt property.
     * 
     * @return
     *     possible object is
     *     {@link XMLGregorianCalendar }
     *     
     */
    public XMLGregorianCalendar getRunAt() {
        return runAt;
    }

    /**
     * Sets the value of the runAt property.
     * 
     * @param value
     *     allowed object is
     *     {@link XMLGregorianCalendar }
     *     
     */
    public void setRunAt(XMLGregorianCalendar value) {
        this.runAt = value;
    }

}
