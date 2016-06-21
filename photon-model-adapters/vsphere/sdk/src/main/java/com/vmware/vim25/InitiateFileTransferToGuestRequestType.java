
package com.vmware.vim25;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlType;


/**
 * <p>Java class for InitiateFileTransferToGuestRequestType complex type.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="InitiateFileTransferToGuestRequestType">
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;sequence>
 *         &lt;element name="_this" type="{urn:vim25}ManagedObjectReference"/>
 *         &lt;element name="vm" type="{urn:vim25}ManagedObjectReference"/>
 *         &lt;element name="auth" type="{urn:vim25}GuestAuthentication"/>
 *         &lt;element name="guestFilePath" type="{http://www.w3.org/2001/XMLSchema}string"/>
 *         &lt;element name="fileAttributes" type="{urn:vim25}GuestFileAttributes"/>
 *         &lt;element name="fileSize" type="{http://www.w3.org/2001/XMLSchema}long"/>
 *         &lt;element name="overwrite" type="{http://www.w3.org/2001/XMLSchema}boolean"/>
 *       &lt;/sequence>
 *     &lt;/restriction>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "InitiateFileTransferToGuestRequestType", propOrder = {
    "_this",
    "vm",
    "auth",
    "guestFilePath",
    "fileAttributes",
    "fileSize",
    "overwrite"
})
public class InitiateFileTransferToGuestRequestType {

    @XmlElement(required = true)
    protected ManagedObjectReference _this;
    @XmlElement(required = true)
    protected ManagedObjectReference vm;
    @XmlElement(required = true)
    protected GuestAuthentication auth;
    @XmlElement(required = true)
    protected String guestFilePath;
    @XmlElement(required = true)
    protected GuestFileAttributes fileAttributes;
    protected long fileSize;
    protected boolean overwrite;

    /**
     * Gets the value of the this property.
     * 
     * @return
     *     possible object is
     *     {@link ManagedObjectReference }
     *     
     */
    public ManagedObjectReference getThis() {
        return _this;
    }

    /**
     * Sets the value of the this property.
     * 
     * @param value
     *     allowed object is
     *     {@link ManagedObjectReference }
     *     
     */
    public void setThis(ManagedObjectReference value) {
        this._this = value;
    }

    /**
     * Gets the value of the vm property.
     * 
     * @return
     *     possible object is
     *     {@link ManagedObjectReference }
     *     
     */
    public ManagedObjectReference getVm() {
        return vm;
    }

    /**
     * Sets the value of the vm property.
     * 
     * @param value
     *     allowed object is
     *     {@link ManagedObjectReference }
     *     
     */
    public void setVm(ManagedObjectReference value) {
        this.vm = value;
    }

    /**
     * Gets the value of the auth property.
     * 
     * @return
     *     possible object is
     *     {@link GuestAuthentication }
     *     
     */
    public GuestAuthentication getAuth() {
        return auth;
    }

    /**
     * Sets the value of the auth property.
     * 
     * @param value
     *     allowed object is
     *     {@link GuestAuthentication }
     *     
     */
    public void setAuth(GuestAuthentication value) {
        this.auth = value;
    }

    /**
     * Gets the value of the guestFilePath property.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    public String getGuestFilePath() {
        return guestFilePath;
    }

    /**
     * Sets the value of the guestFilePath property.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    public void setGuestFilePath(String value) {
        this.guestFilePath = value;
    }

    /**
     * Gets the value of the fileAttributes property.
     * 
     * @return
     *     possible object is
     *     {@link GuestFileAttributes }
     *     
     */
    public GuestFileAttributes getFileAttributes() {
        return fileAttributes;
    }

    /**
     * Sets the value of the fileAttributes property.
     * 
     * @param value
     *     allowed object is
     *     {@link GuestFileAttributes }
     *     
     */
    public void setFileAttributes(GuestFileAttributes value) {
        this.fileAttributes = value;
    }

    /**
     * Gets the value of the fileSize property.
     * 
     */
    public long getFileSize() {
        return fileSize;
    }

    /**
     * Sets the value of the fileSize property.
     * 
     */
    public void setFileSize(long value) {
        this.fileSize = value;
    }

    /**
     * Gets the value of the overwrite property.
     * 
     */
    public boolean isOverwrite() {
        return overwrite;
    }

    /**
     * Sets the value of the overwrite property.
     * 
     */
    public void setOverwrite(boolean value) {
        this.overwrite = value;
    }

}
