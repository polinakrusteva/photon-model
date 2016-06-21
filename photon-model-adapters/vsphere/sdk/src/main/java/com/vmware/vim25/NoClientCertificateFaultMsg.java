
package com.vmware.vim25;

import javax.xml.ws.WebFault;


/**
 * This class was generated by the JAX-WS RI.
 * JAX-WS RI 2.2.9-b14002
 * Generated source version: 2.2
 * 
 */
@WebFault(name = "NoClientCertificateFault", targetNamespace = "urn:vim25")
public class NoClientCertificateFaultMsg
    extends Exception
{

    /**
     * Java type that goes as soapenv:Fault detail element.
     * 
     */
    private NoClientCertificate faultInfo;

    /**
     * 
     * @param faultInfo
     * @param message
     */
    public NoClientCertificateFaultMsg(String message, NoClientCertificate faultInfo) {
        super(message);
        this.faultInfo = faultInfo;
    }

    /**
     * 
     * @param faultInfo
     * @param cause
     * @param message
     */
    public NoClientCertificateFaultMsg(String message, NoClientCertificate faultInfo, Throwable cause) {
        super(message, cause);
        this.faultInfo = faultInfo;
    }

    /**
     * 
     * @return
     *     returns fault bean: com.vmware.vim25.NoClientCertificate
     */
    public NoClientCertificate getFaultInfo() {
        return faultInfo;
    }

}
