
/*
 * @author Jon Deering
Copyright 2011 Saint Louis University. Licensed under the Educational Community License, Version 2.0 (the "License"); you may not use
this file except in compliance with the License.

You may obtain a copy of the License at http://www.osedu.org/licenses/ECL-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
and limitations under the License.
 */
package textdisplay;

import com.hp.hpl.jena.rdf.model.Literal;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.Property;
import com.hp.hpl.jena.rdf.model.RDFList;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.Resource;
import detectimages.Detector;
import detectimages.imageHelpers;
import detectimages.imageProcessor;
import java.awt.image.BufferedImage;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Date;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Stack;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.mail.MessagingException;
import user.User;

/**
 * This class represents a single image. The image can have Line parsing associated with it, and is connected to a collection (Manuscript) and Archive.
 * This is one of the oldest classes in TPEN and has a lot of deprecated classes that reflect a time in the prototype when the Folio object handled all image positional
 * information and the metadata for rendering IPR and shelfmarks.
 * @author jdeering*/
public class Folio {
    
    
    /**
     * Retrieve a value from the config file
     * @param propToken The name of the parameter to be retrieved from the config file
     * @return
     */
    public static String getRbTok(String propToken) {
        ResourceBundle rb = ResourceBundle.getBundle("version");
        String msg = "";
        try {
            msg = rb.getString(propToken);
        } catch (MissingResourceException e) {
            System.err.println("Token ".concat(propToken).concat(" not in Propertyfile!"));
        }
        return msg;
    }
    
    
    Line[] linePositions;
    int[] colStarts;
    int[] colWidths;
    int folioNumber;
    private String archive;
    Boolean zoom = false;
    Boolean forceSingleColumn = false;
    
    
    /**Create a blank Folio, used internally or for creating a bean*/
    public Folio() {
    }
    
    
    /**
     * Add a Folio record identifying Archive, shelfmark, and page and return the unique id associated with it
     * @param collection a way of itentifying this Manuscript (ie ms415)
     * @param pageName name of the page
     * @param imageName full name of the image of this page
     * @param Archive the name of the Archive this collection is housed in
     * @return
     * @throws SQLException
     */
    public static int createFolioRecord(String collection, String pageName, String imageName, String archive, int msID) throws SQLException {
        Connection j = null;
        PreparedStatement stmt=null;
        try {
            String query = "insert into folios (collection,pageName,imageName,archive,msID, uri) values(?,?,?,?,?,?)";
            j = DatabaseWrapper.getConnection();
            stmt = j.prepareStatement(query, PreparedStatement.RETURN_GENERATED_KEYS);
            stmt.setString(1, collection);
            stmt.setString(2, pageName);
            stmt.setString(3, imageName);
            stmt.setString(4, archive);
            stmt.setInt(5, msID);
            stmt.setString(6, imageName);
            stmt.execute();
            ResultSet rs = stmt.getGeneratedKeys();
            if (rs.next()) {
                int toret = rs.getInt(1);
                return (toret);
            } else {
                return 0;
            }
        } finally {
            DatabaseWrapper.closeDBConnection(j);
            DatabaseWrapper.closePreparedStatement(stmt);
        }
    }
    
    
    /**Create a folio record including the SharedCanvas uri associated with the image.*/
    public static int createFolioRecord(String collection, String pageName, String imageName, String archive, int msID, int sequence,String canvas) throws SQLException {
        Connection j = null;
        PreparedStatement stmt=null;
        try {
            String query = "insert into folios (collection,pageName,imageName,archive,msID, uri, sequence, canvas) values(?,?,?,?,?,?,?,?)";
            j = DatabaseWrapper.getConnection();
            stmt = j.prepareStatement(query, PreparedStatement.RETURN_GENERATED_KEYS);
            stmt.setString(1, collection);
            stmt.setString(2, pageName);
            stmt.setString(3, imageName);
            stmt.setString(4, archive);
            stmt.setInt(5, msID);
            stmt.setString(6, imageName);
            stmt.setInt(7, sequence);
            stmt.setString(8, canvas);
            stmt.execute();
            ResultSet rs = stmt.getGeneratedKeys();
            if (rs.next()) {
                int toret = rs.getInt(1);
                return (toret);
            } else {
                return 0;
            }
        } finally {
            DatabaseWrapper.closeDBConnection(j);
            DatabaseWrapper.closePreparedStatement(stmt);
        }
    }
    
    
    /**
     *Fast way to create a new Folio record. 
     * @param collection
     * @param pageName
     * @param imageName
     * @param Archive
     * @param dupe statement to check if this is a duplicate of an existing record
     * @param stmt inserter
     * @return the new Folio unique id, or -1 if this would be a duplicate
     * @throws SQLException
     */
    public static int createFolioRecord(String collection, String pageName, String imageName, String archive, PreparedStatement dupe, PreparedStatement stmt) throws SQLException {
        //Connection j =null;
        try {
            dupe.setString(1, collection);
            dupe.setString(2, pageName);
            dupe.setString(3, imageName);
            dupe.setString(4, archive);
            ResultSet dupeSet = dupe.executeQuery();
            if (dupeSet.next()) {
                return -1;
            }
            stmt.setString(1, collection);
            stmt.setString(2, pageName);
            stmt.setString(3, imageName);
            stmt.setString(4, archive);
            stmt.execute();
            ResultSet rs = stmt.getGeneratedKeys();
            if (rs.next()) {
                int toret = rs.getInt(1);
                return (toret);
            } else {
                return 0;
            }
        } finally {
            //j.close();
        }
    }
    
    
    /**
     * Create a new Folio using an array of lines from the Line detector. Overwrites any old Line definintions
     * @param lines array of Line objects, from the Line detector
     * @param pageNum unique page identifier
     */
    public Folio(detectimages.line[] lines, int pageNum) {
        linePositions = new Line[lines.length];
        int top = 0;
        for (int i = 0; i < lines.length; i++) {
            linePositions[i] = new Line(lines[i].getStartHorizontal(), lines[i].getWidth(), top, lines[i].getStartVertical());
            top = lines[i].getStartVertical();
        }
        folioNumber = pageNum;
        try {
            this.commit();
        } catch (SQLException ex) {
            Logger.getLogger(Folio.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    
    /**
     * @deprecated old method from early prototype that assumes a local Archive called ENAP
     * If we dont have the page number (which is rather arbitrary) we need the page name to look up the page number, and
    go about our buisness. Assumes Archive=ENAP
     * @param pageName the name of the page
     * @param collection the Manuscript identifier
     * @return the unique id of the page
     * @throws SQLException
     */
    public static int getPageNum(String pageName, String collection) throws SQLException {
        Connection j = null;
        PreparedStatement stmt=null;
        try {
            while (pageName.length() < 3) {
                pageName = "0" + pageName;
            }
            String query = "select pageNumber from folios where pageName=? and collection=? and archive='ENAP'";
            j = DatabaseWrapper.getConnection();
            stmt = j.prepareStatement(query);
            stmt.setString(1, pageName);
            stmt.setString(2, collection);
            ResultSet rs = stmt.executeQuery();
            if (!rs.next()) {
                return 0;
            }
            return (rs.getInt(1));
        } finally {
            DatabaseWrapper.closeDBConnection(j);
            DatabaseWrapper.closePreparedStatement(stmt);
        }
    }
    
    
    /**
     * @deprecated this lookup is no longer used
     * If we dont have the page number (which is rather arbitrary) we need the page name to look up the page number, and
    go about our buisness.*/
    public static int getPageNum(String pageName, String collection, String archive) throws SQLException {
        Connection j = null;
        PreparedStatement stmt=null;
        try {
            while (pageName.length() < 3) {
                pageName = "0" + pageName;
            }
            String query = "select pageNumber from folios where pageName=? and collection=? and archive=?";
            j = DatabaseWrapper.getConnection();
            stmt = j.prepareStatement(query);
            stmt.setString(1, pageName);
            stmt.setString(2, collection);
            stmt.setString(3, archive);
            ResultSet rs = stmt.executeQuery();
            if (!rs.next()) {
                return 0;
            }
            return (rs.getInt(1));
        } finally {
            DatabaseWrapper.closeDBConnection(j);
            DatabaseWrapper.closePreparedStatement(stmt);
        }
    }
    
    
    /**Find the folio number of the folio with the given canvas*/
    public static int getFolioFromCanvas(String canvas) throws SQLException
    {
        String query="select pageNumber from folios where canvas=?";
        Connection j=null;
        PreparedStatement ps=null;
        try{
            j=DatabaseWrapper.getConnection();
            ps=j.prepareStatement(query);
            ps.setString(1, canvas);
            ResultSet rs=ps.executeQuery();
            if(rs.next())
            {
                return rs.getInt(1);
            }
        }
        finally
        {
            DatabaseWrapper.closeDBConnection(j);
            DatabaseWrapper.closePreparedStatement(ps);
        }
        return 0;
    }
    
    
    /**
     * @deprecated zoom is now handled by the ui
     * When zoom is set, an image with double the normal resolution will be used, and all of the positional information
    is adjusted accordingly*/
    public void setZoom() {
        zoom = true;
    }
    
    
    /**
     * Returns the unique identifier for this Folio within TPEN
     * @return the unique identifier for this Folio if it has been properly instantiated
     */
    public int getFolioNumber() {
        return this.folioNumber;
    }
    
    
    /**Does this Folio have IPR restrictions that the user will need to be made aware of?
     *  @deprecated IPR restriction requests are now serviced by the Manuscript object
     */
    public Boolean hasIPRRestrictions() {
        return false;
    }
    
    
    /**
     * @deprecated IPR restriction requests are now serviced by the Manuscript object
     * Return the IPR message for this image, not implemented yet*/
    public String getIPRRestrictions() {
        return "";
        //return ("Use of this image is goverened by US and international copyright law. Permission to use it within this tool has been provided by the owner of those rights, " +
        //      ", however, permission for any other use has not been given. By accepting this, you agree to abide by these restrictions.");
    }
    
    
    /**Returns each Line of the ms represented by a properly sized div*/
    public String getLinesAsDivs() {
        String toret = "";
        String onClick = "onclick=\"deleteme(this,event);\"";
        for (int i = 0; i < linePositions.length; i++) {
            if (i % 2 != 0) {
                toret += "<div class=\"line\" style=\"position:absolute;top:" + linePositions[i].top + "px;width:100%;height:" + (linePositions[i].bottom - linePositions[i].top) + "px;background-color:aqua;\" " + onClick + " >";
                toret += "</div>";
            } else {
                toret += "<div class=\"line\" style=\"position:absolute;top:" + linePositions[i].top + "px;width:100%;height:" + (linePositions[i].bottom - linePositions[i].top) + "px;background-color:red;\" " + onClick + " >";
                toret += "</div>";
            }
        }
        return toret;
    }
    
    
    /**Returns each Line of the ms represented by a properly sized div accounting for columns*/
    public String getLinesAsDivsWithCols() {
        String toret = "";
        String onClick = "onclick=\"deleteme(this,event);\"";
        for (int i = 0; i < linePositions.length; i++) {
            if (i % 2 != 0 && linePositions[i] != null) {
                toret += "<div class=\"line\" style=\"position:absolute;top:";
                toret += linePositions[i].top;
                toret += "px;left:" + linePositions[i].left;
                if (linePositions[i].getWidth() > 0) {
                    toret += "px;width:" + linePositions[i].getWidth();
                } else {
                    toret += "px;width:200";
                }
                toret += "px;height:" + (linePositions[i].bottom - linePositions[i].top);
                toret += "px;background-color:aqua;\" " + onClick + " >";
                toret += "</div>\n";
            } else {
                if (linePositions[i] != null) {
                    toret += "<div class=\"line\" style=\"position:absolute;top:" + linePositions[i].top + "px;width:100%;left:" + linePositions[i].left;
                    if (linePositions[i].getWidth() > 0) {
                        if (linePositions.length > i + 1 && linePositions[i].getLeft() == linePositions[i + 1].getLeft()) {
                            toret += "px;height:" + (linePositions[i + 1].top - linePositions[i].top);
                        } else {
                            toret += "px;height:" + (linePositions[i].bottom - linePositions[i].top);
                        }
                        toret += "px;width:" + linePositions[i].getWidth() + "px;background-color:red;\" " + onClick + " >";
                    } else {
                        toret += "px;width:200" + "px;height:" + (linePositions[i].bottom - linePositions[i].top) + "px;background-color:red;\" " + onClick + " >";
                    }
                    toret += "</div>";
                }
            }
        }
        return toret;
    }
    
    
    /**
     * Create a set of HTML objects to display an overlay using the coordinates from lines
     * @param lines array of lines that should be rendered
     * @return HTML
     */
    public String getLinesAsDivsWithCols(Line[] lines) {
        Line[] tmp = this.linePositions;
        this.linePositions = lines;
        String toret = "";
        String onClick = "onclick=\"deleteme(this,event);\"";
        for (int i = 0; i < linePositions.length; i++) {
            if (i % 2 != 0 && linePositions[i] != null) {
                toret += "<div class=\"line\" style=\"position:absolute;top:";
                toret += linePositions[i].top;
                toret += "px;left:" + linePositions[i].left;
                if (linePositions[i].getWidth() > 0) {
                    toret += "px;width:" + linePositions[i].getWidth();
                } else {
                    toret += "px;width:200";
                }
                if (linePositions.length > i + 1 && linePositions[i].getLeft() == linePositions[i + 1].getLeft()) {
                    toret += "px;height:" + (linePositions[i + 1].top - linePositions[i].top);
                } else {
                    toret += "px;height:" + (linePositions[i].bottom - linePositions[i].top);
                }
                toret += "px;background-color:aqua;\" " + onClick + " >";
                toret += "</div>\n";
            } else {
                if (linePositions[i] != null) {
                    toret += "<div class=\"line\" style=\"position:absolute;top:" + linePositions[i].top + "px;width:100%;left:" + linePositions[i].left;
                    if (linePositions[i].getWidth() > 0) {
                        toret += "px;width:" + linePositions[i].getWidth() + "px;height:" + (linePositions[i].bottom - linePositions[i].top) + "px;background-color:red;\" " + onClick + " >";
                    } else {
                        toret += "px;width:200" + "px;height:" + (linePositions[i].bottom - linePositions[i].top) + "px;background-color:red;\" " + onClick + " >";
                    }
                    toret += "</div>";
                }
            }
        }
        this.linePositions = tmp;
        return toret;
    }
    
    
    /**Allows the page name, which is displayed in the UI, to be updated. Used on manuscript admin.jsp*/
    public void setPageName(String name) throws SQLException
    {
        String query="update folios set pageName=? where pageNumber=?";
        Connection j=null;
        PreparedStatement ps=null;
        try {
            j=DatabaseWrapper.getConnection();
            ps=j.prepareStatement(query);
            ps.setString(1, name);
            ps.setInt(2, folioNumber);
            ps.execute();
        }
        finally {
            DatabaseWrapper.closeDBConnection(j);
            DatabaseWrapper.closePreparedStatement(ps);
        }
    }
    
    
    /**
     * @deprecated the image parser doesnt need to be told how many columns to expect any longer
     * Force the Line detector to treat this page as a single column for purposes of Line detection*/
    public void setMSSingleCol(Boolean val) throws SQLException {
        Connection j = null;
        PreparedStatement stmt=null;
        try {
            String query = "update folios set force=? where collection=?";
            int toSend;
            if (val) {
                toSend = 0;
            } else {
                toSend = 1;
            }
            j = DatabaseWrapper.getConnection();
            stmt = j.prepareStatement(query);
            stmt.setInt(1, toSend);
            stmt.setString(2, this.getCollectionName());
            stmt.execute();
        } finally {
            DatabaseWrapper.closeDBConnection(j);
            DatabaseWrapper.closePreparedStatement(stmt);
        }
    }
    
    
    /**Return an html image element that will show just the requested Line.*/
    public String getLineImage(int lineNo) {
        String toret = "";
        Line thisLine = linePositions[lineNo - 1];
        if (thisLine.getHeight() > 0) {
            toret += "<img src=\"";
            //the image should be p{Folio}.jpg
            toret += "p" + this.folioNumber + ".jpg";
            toret += "/>";
        }
        return toret;
    }
    
    
    /**Constructor that doesnt force line detection.*/
    public Folio(Boolean nolines, int folioNumber) {
        this.folioNumber = folioNumber;
    }
    
    
    /**Used in the Transcription search to populate this Folio, so it can then be used to include an image of the Line that the search result
    points to*/
    public Folio(int folioNumber, Boolean cols) throws SQLException {
        Connection j = null;
        PreparedStatement stmt=null;
        PreparedStatement stmt2=null;
        PreparedStatement stmt3=null;
        try {
            this.folioNumber = folioNumber;
            //If this Folio has an existing record, retrieve it. Otherwise, get a blank one.
            j = DatabaseWrapper.getConnection();
            stmt = j.prepareStatement("Select count(id) from imagepositions where folio=? and width>0 order by colstart,top");
            stmt2 = j.prepareStatement("delete from imagepositions where width=0");
            stmt.setInt(1, folioNumber);
            ResultSet rs = stmt.executeQuery();
            rs.next();
            int size = rs.getInt(1);
            linePositions = new Line[size];
            stmt3 = j.prepareStatement("Select * from imagepositions where folio=? and width>0 order by colstart,top");
            stmt3.setInt(1, folioNumber);
            rs = stmt3.executeQuery();
            int i = 0;
            while (rs.next()) {
                linePositions[i] = new Line(0, 0, 0, 0);
                linePositions[i].bottom = rs.getInt("bottom");
                linePositions[i].top = rs.getInt("top");
                linePositions[i].left = rs.getInt("colstart");
                linePositions[i].right = linePositions[i].left + rs.getInt("width");
                i++;
            }
            stmt2 = j.prepareStatement("select * from folios where pageNumber=?");
            stmt2.setInt(1, folioNumber);
            rs = stmt2.executeQuery();
            if (rs.next()) {
                archive = rs.getString("archive");
                if (rs.getInt("force") == 0) {
                    this.forceSingleColumn = true;
                }
            }
            if (i == 0) {
                //run the Line detector, this was a Folio we knew about but never ran detection on.
                detect(folioNumber, this.forceSingleColumn);
                
                stmt.setInt(1, folioNumber);
                rs = stmt.executeQuery();
                rs.next();
                size = rs.getInt(1);
                
                linePositions = new Line[size];
                rs = stmt3.executeQuery();
                
                while (rs.next()) {
                    linePositions[i] = new Line(0, 0, 0, 0);
                    linePositions[i].bottom = rs.getInt("bottom");
                    linePositions[i].top = rs.getInt("top");
                    linePositions[i].left = rs.getInt("colstart");
                    linePositions[i].right = linePositions[i].left + rs.getInt("width");
                    i++;
                }
                
            }
            
        } finally {
            DatabaseWrapper.closeDBConnection(j);
            DatabaseWrapper.closePreparedStatement(stmt);
            DatabaseWrapper.closePreparedStatement(stmt2);
            DatabaseWrapper.closePreparedStatement(stmt3);
        }
    }
    
    
    /**Create a dropdown of folios available for Transcription, even if a Transcription has never been attempted.*/
    public String getFolioDropDown() throws SQLException {
        Connection j = null;
        PreparedStatement stmt=null;
        try {
            String toret = "";
            j = DatabaseWrapper.getConnection();
            String qry = "select * from folios where archive=? and collection=? ";
            stmt = j.prepareStatement(qry);
            stmt.setString(1, this.archive);
            stmt.setString(2, this.getCollectionName());
            
            ResultSet rs = stmt.executeQuery();
            Stack<String> pageNames = new Stack();
            Stack<Integer> pageNumbers = new Stack();
            while (rs.next()) {
                //toret+="<option value=\""+rs.getInt("pageNumber")+"\">"+textdisplay.Archive.getShelfMark(rs.getString("Archive"))+" "+rs.getString("collection")+" "+rs.getString("pageName")+"</option>";
                //pageNames.add(rs.getString("pageName"));
                pageNames.add(zeroPadLastNumberFourPlaces(rs.getString("pageName").replace("-000", "")));
                pageNumbers.add(rs.getInt("pageNumber"));
            }
            int[] pageNumbersArray = new int[pageNumbers.size()];
            String[] paddedPageNameArray = new String[pageNames.size()];
            
            for (int i = 0; i < paddedPageNameArray.length; i++) {
                paddedPageNameArray[i] = pageNames.elementAt(i);
                pageNumbersArray[i] = pageNumbers.get(i);
            }
            
            for (int i = 0; i < paddedPageNameArray.length; i++) {
                for (int k = 0; k < paddedPageNameArray.length - 1; k++) {
                    if (paddedPageNameArray[k].compareTo(paddedPageNameArray[k + 1]) > 0) {
                        String tmpStr = paddedPageNameArray[k];
                        paddedPageNameArray[k] = paddedPageNameArray[k + 1];
                        paddedPageNameArray[k + 1] = tmpStr;
                        int tmpInt = pageNumbersArray[k];
                        pageNumbersArray[k] = pageNumbersArray[k + 1];
                        pageNumbersArray[k + 1] = tmpInt;
                        
                    }
                }
            }
            qry = "select * from folios where pageNumber=?";
            stmt = j.prepareStatement(qry);
            for (int i = 0; i < pageNumbersArray.length; i++) {
                stmt.setInt(1, pageNumbersArray[i]);
                rs = stmt.executeQuery();
                rs.next();
                Manuscript ms = new Manuscript(rs.getInt("msID"), true);
                toret += "<option value=\"" + rs.getInt("pageNumber") + "\">" + ms.getShelfMark() + " " + rs.getString("pageName") + "</option>";
            }
            
            return toret;
        } finally {
            DatabaseWrapper.closeDBConnection(j);
            DatabaseWrapper.closePreparedStatement(stmt);
        }
    }
    
    
    /**Zero pad image names to 4 places for sorting purposes when the ordering of the images is not made clear by stored values*/
    public static String zeroPadLastNumberFourPlaces(String name) {
        for (int i = name.length() - 1; i >= 0; i--) {
            if (Character.isDigit(name.charAt(i))) {
                //count the number of digits that preceed this one, if it is less than 3, padd with zeros
                int count = 0;
                for (int j = i; j >= 0; j--) {
                    if (Character.isDigit(name.charAt(j))) {
                        count++;
                        if (j == 0) {
                            //padd
                            if (count == 2) {
                                name = name.substring(0, j) + "0" + name.substring(j);
                            }
                            if (count == 1) {
                                name = name.substring(0, j) + "00" + name.substring(j);
                            }
                            if (count == 0) {
                                name = name.substring(0, j) + "000" + name.substring(j);
                            }
                            //return name;
                            count=0;
                        }
                    } else {
                        if (count < 3) {
                            //padd
                            if (count == 2) {
                                name = name.substring(0, j + 1) + "0" + name.substring(j + 1);
                            }
                            if (count == 1) {
                                name = name.substring(0, j + 1) + "00" + name.substring(j + 1);
                            }
                            //if (count == 0) {
                            //    name = name.substring(0, j + 1) + "000" + name.substring(j + 1);
                            //}
                        }
                        count=0;
                        //return name;
                    }
                }
                
            }
        }
        return name;
    }
    
    
    /**Create the items for a dropdown list of all people who have created transcriptions of the
    page in question*/
    public String getTranscriberDropDown(int folioNum) throws SQLException {
        Connection j = null;
        PreparedStatement stmt=null;
        try {
            String query = "select distinct(creator),collection,archive,pageName from transcription join folios on transcription.folio=folios.pageNumber where folio=? order by archive,collection,pageName";
            String toret = "";
            j = DatabaseWrapper.getConnection();
            stmt = j.prepareStatement(query);
            stmt.setInt(1, folioNum);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                user.User tmpuser = new user.User(rs.getInt("creator"));
                toret += "<option value=\"?archive=" + rs.getString("archive") + "&collection=" + rs.getString("collection") + "&page=" + rs.getString("pageName").replace(" ", "%20") + "&uid=" + rs.getInt("creator") + "\">" + tmpuser.getLname() + ", " + tmpuser.getFname() + "</option>";
            }
            return toret;
        } finally {
            DatabaseWrapper.closeDBConnection(j);
            DatabaseWrapper.closePreparedStatement(stmt);
        }
    }
    
    
    /**Create a dropdown of available folios which have existing transcriptions*/
    public String getTranscriptionFolioDropDown() throws SQLException {
        Connection j = null;
        PreparedStatement stmt=null;
        try {
            String toret = "";
            String qry = "select Distinct(folio),pageNumber,archive,collection,pageName from transcription join folios on transcription.folio=pageNumber order by folios.collection";
            j = DatabaseWrapper.getConnection();
            stmt = j.prepareStatement(qry);
            ResultSet rs = stmt.executeQuery();
            
            while (rs.next()) {
                Manuscript ms = new Manuscript(rs.getString("collection"), rs.getString("archive"));
                toret += "<option value=\"?archive=" + rs.getString("archive") + "&collection=" + rs.getString("collection") + "&page=" + rs.getString("pageName").replace(" ", "%20") + "\">" + ms.getShelfMark() + " " + rs.getString("pageName") + "</option>";
            }
            return toret;
        } finally {
            DatabaseWrapper.closeDBConnection(j);
            DatabaseWrapper.closePreparedStatement(stmt);
        }
    }
    
    
    /**Get the name of the image associated with this Folio*/
    public String getImageName() throws SQLException {
        Connection j = null;
        PreparedStatement stmt=null;
        try {
            String toret = "";
            String qry = "select * from folios where pageNumber=?";
            j = DatabaseWrapper.getConnection();
            stmt = j.prepareStatement(qry);
            stmt.setInt(1, folioNumber);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                toret += rs.getString("imageName");
            }
            if (toret.contains(".jpg")) {
                toret = toret.replaceAll("\\.jpg", "");
            }
            return toret;
        } finally {
            DatabaseWrapper.closeDBConnection(j);
            DatabaseWrapper.closePreparedStatement(stmt);
        }
    }
    
    
    /**Get the image url of a page without instantiating a Folio object*/
    public static String getImageName(String pageName) throws SQLException {
        Connection j = null;
        PreparedStatement stmt=null;
        try {
            String toret = "";
            String qry = "select * from folios where pageName=? order by pageNumber desc limit 1";
            j = DatabaseWrapper.getConnection();
            stmt = j.prepareStatement(qry);
            stmt.setString(1, pageName);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                toret += rs.getString("imageName");
            }
            if (toret.contains(".jpg")) {
                toret = toret.replaceAll("\\.jpg", "");
            }
            return toret;
        } finally {
            DatabaseWrapper.closeDBConnection(j);
            DatabaseWrapper.closePreparedStatement(stmt);
        }
    }
    
    
    /**Retrieve the stored image url*/
    public String getImageURL() throws SQLException {
        
        String query = "select uri from folios where pageNumber=?";
        if (this.getArchive().compareTo("CEEC") == 0 || this.getArchive().compareTo("ecodices") == 0) {
            query = "select imageName from folios where pageNumber=?";
        }
        Connection j = null;
        PreparedStatement ps=null;
        try {
            j = DatabaseWrapper.getConnection();
            ps = j.prepareStatement(query);
            ps.setInt(1, folioNumber);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString(1);
            }
            return "";
        } finally {
            DatabaseWrapper.closeDBConnection(j);
            DatabaseWrapper.closePreparedStatement(ps);
        }
    }
    
    
    /**Build an image url for the requested page in the requested digital Archive
    @Depricated*/
    /*
    public String getImageURL(String collection,String pageName, String Archive) throws SQLException
    {
    if(Archive.compareTo("ENAP")==0 && collection.compareTo("Manuscript 415")!=0)
    return "/images/"+this.getImageName()+"z.jpg";
    return textdisplay.Archive.getURLResizedLarge(this.folioNumber,2000);
    }*/
    /**Build an image url for the requested page in the requested digital Archive*/
    public static String getImageURL(int folioNum) throws SQLException {
        return textdisplay.Archive.getURLResizedLarge(folioNum, 2000);
    }
    
    
    /**
     * Build a url for a 1000 pixel height image.
     * @return String containing the url for a 1000 pixel height version of the image.
     * @throws SQLException 
     */
    public String getImageURLResize() throws SQLException {
        String toret="";
        toret= textdisplay.Archive.getURLResized(this.folioNumber, 1000);
        if (toret==null)
            return "";
        return toret;
    }
    
    
    /**
     * Build a url for a scaled image
     * @param size requested height in pixels
     * @return url for the resized image
     * @throws SQLException 
     */
    public String getImageURLResize(int size) throws SQLException 
    {
        String toret="";
        try
        {
            toret= textdisplay.Archive.getURLResized(this.folioNumber, size);
        }
        catch(Exception e)
        {
            return "";
        }
        
        if(toret==null)
            return "";
        return toret;
    }
    
    
    /**
     * Get the Folio object associated with a particular image name or image url
     * @param imageName image name or image url
     * @return Folio object or null if it doesnt exist
     * @throws SQLException 
     */
    public static Folio getImageNameFolio(String imageName) throws SQLException {
        Connection j = null;
        PreparedStatement stmt=null;
        try {
            j = DatabaseWrapper.getConnection();
            String query = "select pageNumber from folios where imageName=?";
            stmt = j.prepareStatement(query);
            stmt.setString(1, imageName);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                int idno = rs.getInt(1);
                return new Folio(idno);
            } else {
                return null;
            }
        } finally {
            DatabaseWrapper.closeDBConnection(j);
            DatabaseWrapper.closePreparedStatement(stmt);
        }
    }
    
    
    /**
     * Get the name of the collection this MS page belongs to
     * @return collection name or empty string if this object isnt populated
     * @throws SQLException
     */
    public String getCollectionName() throws SQLException {
        Connection j = null;
        PreparedStatement stmt=null;
        
        try {
            String toret = "";
            String qry = "select * from folios where pageNumber=?";
            j = DatabaseWrapper.getConnection();
            stmt = j.prepareStatement(qry);
            stmt.setInt(1, folioNumber);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                toret += rs.getString("collection");
            }
            return toret;
        } finally {
            DatabaseWrapper.closeDBConnection(j);
            DatabaseWrapper.closePreparedStatement(stmt);
        }
    }
    
    
    public String getCanvas() throws SQLException
    {
        Connection j = null;
        PreparedStatement stmt=null;
        try {
            String toret = "";
            String qry = "select * from folios where pageNumber=?";
            j = DatabaseWrapper.getConnection();
            stmt = j.prepareStatement(qry);
            stmt.setInt(1, folioNumber);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                toret += rs.getString("canvas");
            }
            
            return toret;
        } finally {
            DatabaseWrapper.closeDBConnection(j);
            DatabaseWrapper.closePreparedStatement(stmt);
        }
    }
    
    
    /**
     * Get the page name for this Folio
     * @return the page name ( 1. V for example) or "" if this object isnt populated
     * @throws SQLException 
     */
    public String getPageName() throws SQLException {
        Connection j = null;
        PreparedStatement stmt=null;
        try {
            String toret = "";
            String qry = "select * from folios where pageNumber=?";
            j = DatabaseWrapper.getConnection();
            stmt = j.prepareStatement(qry);
            stmt.setInt(1, folioNumber);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                toret += rs.getString("pageName");
            }
            
            return toret;
        } finally {
            DatabaseWrapper.closeDBConnection(j);
            DatabaseWrapper.closePreparedStatement(stmt);
        }
    }
    
    
    /**
     * Constructor for a Folio based on the unique id. Most common constructor. Will generate Line parsing for this image
     * if it hasnt been parsed previously to ensure Line positions are available, so can take a while to run in some cases.
     * @param folioNumber Folio unique id
     * @throws SQLException 
     */
    public Folio(int folioNumber) throws SQLException {
        Connection j = null;
        PreparedStatement stmt=null;
        try {
            this.folioNumber = folioNumber;
            //If this Folio has an existing record, retrieve it. Otherwise, get a blank one.
            j = DatabaseWrapper.getConnection();
            stmt = j.prepareStatement("Select count(*) from imagepositions where folio=? and top<bottom order by colstart,top");
            stmt.setInt(1, folioNumber);
            ResultSet rs = stmt.executeQuery();
            int size = 0;
            if (rs.next()) {
                size = rs.getInt(1);
            }
            
            linePositions = new Line[size];
            stmt = j.prepareStatement("Select * from imagepositions where folio=? and top<bottom order by colstart, top");
            stmt.setInt(1, folioNumber);
            rs = stmt.executeQuery();
            int i = 0;
            while (rs.next()) {
                linePositions[i] = new Line(0, 0, 0, 0);
                linePositions[i].bottom = rs.getInt("bottom");
                linePositions[i].top = rs.getInt("top");
                linePositions[i].left = rs.getInt("colstart");
                linePositions[i].right = rs.getInt("width") + linePositions[i].left;
                i++;
            }
            
            stmt = j.prepareStatement("select * from folios where pageNumber=?");
            stmt.setInt(1, folioNumber);
            rs = stmt.executeQuery();
            if (rs.next()) {
                archive = rs.getString("archive");
            }
            
        } finally {
            DatabaseWrapper.closeDBConnection(j);
            DatabaseWrapper.closePreparedStatement(stmt);
        }
    }
    
    
    public Boolean isReadyForPaleographicAnalysis() throws SQLException
    {
        String query="select paleography from folios where pageNumber=? and paleography!='0000-00-00 00:00:00'";
        Connection j = null;
        PreparedStatement ps=null;
        try {
            j=DatabaseWrapper.getConnection();
            ps=j.prepareStatement(query);
            ps.setInt(1, folioNumber);
            ResultSet rs=ps.executeQuery();
            if(rs.next())
            {
                Date d=rs.getDate("paleography");
                if(d.getTime()!=0)
                    return true;
            }
        }
        finally {
            DatabaseWrapper.closeDBConnection(j);
            DatabaseWrapper.closePreparedStatement(ps);
        }
        return false;
    }
    
    
    /**Is this image cached? Useful for setting user expectations, and restricting the nmber of new image loads over a given period of time for the paleography UI*/
    public Boolean isCached() throws SQLException
    {
        String query="select folio from imageCache where folio=? ";
        Connection j = null;
        PreparedStatement ps=null;
        try {
            j=DatabaseWrapper.getConnection();
            ps=j.prepareStatement(query);
            ps.setInt(1, folioNumber);
            ResultSet rs=ps.executeQuery();
            if(rs.next())
            {
                return true;
            }
            else
            {
                return false;
            }
        }
        finally {
            DatabaseWrapper.closeDBConnection(j);
            DatabaseWrapper.closePreparedStatement(ps);
        }
    }
    
    
    /**
     * @deprecated 
     * Deprecated constructor building a Folio from its unique id. This provided a db connection, so
     * in cases where a large number of folios needed to be instantiated before connection pooling was working
     * this elimintated the large number of connection open and closes.
     * @param folioNumber
     * @param j
     * @throws SQLException 
     */
    public Folio(int folioNumber, Connection j) throws SQLException {
        PreparedStatement stmt=null;
        
        this.folioNumber = folioNumber;
        //If this Folio has an existing record, retrieve it. Otherwise, get a blank one.
        
        stmt = j.prepareStatement("Select count(*) from imagepositions where folio=? and top<bottom order by colstart,top");
        stmt.setInt(1, folioNumber);
        ResultSet rs = stmt.executeQuery();
        int size = 0;
        if (rs.next()) {
            size = rs.getInt(1);
        }
        
        linePositions = new Line[size];
        stmt = j.prepareStatement("Select * from imagepositions where folio=? and top<bottom order by colstart, top");
        stmt.setInt(1, folioNumber);
        rs = stmt.executeQuery();
        int i = 0;
        while (rs.next()) {
            linePositions[i] = new Line(0, 0, 0, 0);
            linePositions[i].bottom = rs.getInt("bottom");
            linePositions[i].top = rs.getInt("top");
            linePositions[i].left = rs.getInt("colstart");
            linePositions[i].right = rs.getInt("width") + linePositions[i].left;
            i++;
        }
        
        stmt = j.prepareStatement("select * from folios where pageNumber=?");
        stmt.setInt(1, folioNumber);
        rs = stmt.executeQuery();
        if (rs.next()) {
            archive = rs.getString("archive");
        }
        
    }
    
    
    /**
     * Protected constructor for a Folio that expects 3 prepared statements. This makes repreat calls work quickly. Used when adding
     * new manuscripts containing hundreds of pages.
     * @param folioNumber
     * @param stmt prepared version "Select count(*) from imagepositions where Folio=? and top<bottom order by colstart,top"
     * @param stmt2 prepared version of "Select * from imagepositions where Folio=? and top<bottom order by colstart, top"
     * @param stmt3 prepared version of "select * from folios where pageNumber=?"
     * @throws SQLException 
     */
    protected Folio(int folioNumber, PreparedStatement stmt, PreparedStatement stmt2, PreparedStatement stmt3) throws SQLException {
        this.folioNumber = folioNumber;
        //If this Folio has an existing record, retrieve it. Otherwise, get a blank one.
        //j = DatabaseWrapper.getConnection();
        // PreparedStatement stmt = j.prepareStatement("Select count(*) from imagepositions where Folio=? and top<bottom order by colstart,top");
        stmt.setInt(1, folioNumber);
        ResultSet rs = stmt.executeQuery();
        int size = 0;
        if (rs.next()) {
            size = rs.getInt(1);
        }
        
        linePositions = new Line[size];
        // stmt2 = j.prepareStatement("Select * from imagepositions where Folio=? and top<bottom order by colstart, top");
        stmt2.setInt(1, folioNumber);
        rs = stmt2.executeQuery();
        int i = 0;
        while (rs.next()) {
            linePositions[i] = new Line(0, 0, 0, 0);
            linePositions[i].bottom = rs.getInt("bottom");
            linePositions[i].top = rs.getInt("top");
            linePositions[i].left = rs.getInt("colstart");
            linePositions[i].right = rs.getInt("width") + linePositions[i].left;
            i++;
        }
        
        //stmt3=j.prepareStatement("select * from folios where pageNumber=?");
        stmt3.setInt(1, folioNumber);
        rs = stmt3.executeQuery();
        if (rs.next()) {
            archive = rs.getString("archive");
        }
    }
    
    
    /**
     * Run Line detection on columns that have been saved for this Folio previously. Typical procedure is to save the columns as lines, then call this. The result
     * will be that lines are added within each of those columns, and the columns are removed.
     */
    public void detectInColumns() {
        try {
            Stack<detectimages.line> newLines = new Stack();
            
            for (int i = 0; i < this.linePositions.length; i++) {
                if (this.linePositions[i].right != 0) {
                    detectimages.line[] tmp = detect(i);
                    System.out.print("Recieved " + tmp.length + " lines in folio on line 937\n");
                    System.out.flush();
                    for (int j = 0; j < tmp.length; j++) {
                        newLines.add(tmp[j]);
                    }
                }
            }
            Line[] newLineArray = new Line[newLines.size()];
            for (int i = 0; i < newLineArray.length; i++) {
                newLineArray[i] = new Line(newLines.get(i));
            }
            System.out.print("updating with " + newLineArray.length + " lines\n");
            System.out.flush();
            this.update(newLineArray);
        } catch (Exception e) {
            //just log any errors, nothing else can be done at this point.
            Logger.getLogger(Folio.class.getName()).log(Level.SEVERE, null, e);
        }
    }
    
    
    /**
     * Run the Line detection procedure on the column(s) passed in, and return the results
     * @param lines Array of lines where each Line will be treated as a column
     * @return array of lines that were detected
     */
    public Line[] detectInColumns(Line[] lines) {
        Line[] publicLinePositions = this.linePositions;
        this.linePositions = lines;
        try {
            Stack<detectimages.line> newLines = new Stack();
            Logger.getLogger("folio").log(Level.SEVERE, ("Recieved " + linePositions.length + " lines in folio on line 961"));
            System.out.print("Recieved " + linePositions.length + " lines in folio on line 961\n");
            System.out.flush();
            
            for (int i = 0; i < this.linePositions.length; i++) {
                if (this.linePositions[i].right != 0) {
                    detectimages.line[] tmp = detect(i);
                    if(tmp!=null)
                    {
                    for (int j = 0; j < tmp.length; j++) {
                        newLines.add(tmp[j]);
                    }
                    }
                }
            }
            Line[] newLineArray = new Line[newLines.size()];
            for (int i = 0; i < newLineArray.length; i++) {
                newLineArray[i] = new Line(newLines.get(i));
            }
            //this.linePositions = publicLinePositions;
            //this.update(newLineArray);
            return newLineArray;
        } catch (Exception e) {
            //just log any errors, nothing else can be done at this point.
            Logger.getLogger(Folio.class.getName()).log(Level.SEVERE, null, e);
            return null;
        }
    }
    
    
    /**
     * Return the width of the image if the image were scaled to 1000 pixels in height. Height=1000 is how we record all image positional data.
     * It can fail if image retrieval fails, because this has to actually fetch a copy of the image.
     * @return the width or -1 if this failed. 
     * @throws SQLException
     */
    public int getImageWidth() throws SQLException {
        try {
            String imageURL = getImageURL();
            //imageURL="http://localhost:8080/TPEN/"+getImageURL(getCollectionName(), getPageName(), getArchive());
            System.out.print("fetching "+Folio.getRbTok("SERVERCONTEXT") + getImageURL(this.folioNumber)+"&code="+Folio.getRbTok("imageCode"));
            BufferedImage img = imageHelpers.readAsBufferedImage(new URL(Folio.getRbTok("SERVERCONTEXT") + getImageURL(this.folioNumber)+"&code="+Folio.getRbTok("imageCode")));
            int height = 1000;
            int width = (int) ((height / (double) img.getHeight()) * img.getWidth());
            return width;
        } catch (Exception ex) {
            return -1;
        }
    }
    
    
    /**
     * Run Line detection on a known column to find the lines within.
     *@param colNum the number of the Line object in linePositions that represents the column within which lines should be detected
     * @return lines detected in the column, or null in case of an unrecoverable error
     */
    private detectimages.line[] detect(int colNum) {
        try {
            String imageURL = Folio.getRbTok("SERVERCONTEXT") + getImageURL(this.folioNumber)+"&code="+Folio.getRbTok("imageCode");
            BufferedImage img = imageHelpers.readAsBufferedImage(new URL(imageURL));
            int height = 1000;
            int width = (int) ((height / (double) img.getHeight()) * img.getWidth());
            img = imageHelpers.scale(img, height, width);
            int x = linePositions[colNum].left;
            int y = linePositions[colNum].top;
            int w = linePositions[colNum].right - linePositions[colNum].left;
            int h = linePositions[colNum].getHeight();
            BufferedImage tmp = img.getSubimage(x, y, w, h);//new BufferedImage(linePositions[colNum].getWidth(), linePositions[colNum].getHeight(),img.getType());
            tmp = imageHelpers.cloneBufferedImage(tmp);
            imageHelpers.writeImage(tmp, "/usr/tmpwrite.jpg");
            BufferedImage bin = imageHelpers.binaryThreshold(tmp, 0);
            img = tmp;
            Detector myDetector = new Detector(img, bin);
            if (Folio.getRbTok("debug").compareTo("true") == 0) {
                myDetector.debugLabel = "fol_" + this.getFolioNumber() + "_" + System.currentTimeMillis();
            }
            myDetector.smeared = bin;
            //remove huge sections of black from the binarized image, they only cause trouble
            myDetector.graphical = true;
            //this is a known column, it wont have any columns within it so do not let the detector search for mltiple columns
            myDetector.forceSingle = true;
            myDetector.vsmearDist = 15;
            myDetector.hsmearDist = 15;
            try {
                myDetector.detect();
            } catch (ArithmeticException e) {
                //this is for a potential division by zero if no lines are found. The error is likely no longer possible, but should it occur, 
                //no need to do anything, the folowing code works fine in that event.
            }
            detectimages.line[] flipped = myDetector.lines.toArray(new detectimages.line[myDetector.lines.size()]);
            System.out.print("detected " + flipped.length + " lines\n");
            //because the Line detection ran on a cropped column, add the column start x and y to the coordinates r
            //returned by the detector
            for (int i = 0; i < flipped.length; i++) {
                flipped[i].setStartHorizontal(flipped[i].getStartHorizontal() + x);
                flipped[i].setStartVertical(flipped[i].getStartVertical() + y - flipped[i].getDistance());
            }
            //force the top Line to begin at the top of the user defined column, so no portion of the original column is truncated in this process
            if(flipped.length==0)
                return null;
            flipped[0].setStartVertical(y);
            //adjust the Line heights so that the bottom of one Line meets the top of the following Line
            for (int i = 0; i < flipped.length - 1; i++) {
                flipped[i].setDistance(flipped[i + 1].getStartVertical() - flipped[i].getStartVertical());
            }
            //Force the bottom Line to stretch the bottom of the user specified column, so no portion of the original column is truncated in this process
            int il = y + h - flipped[flipped.length - 1].getStartVertical();
            flipped[flipped.length - 1].setDistance(il);
            return flipped;
        } catch (Exception ex) {
            //because the Line parsing process is developed in tandem with this, catch a potential unforseen error and log it rather than throwing it to the jsp
            Logger.getLogger(Folio.class.getName()).log(Level.SEVERE, null, ex);
        }
        return null;
    }
    
    
    /**Detect lines in the image.
     *@param force if true, force the image to be treated as only a single column
     *@param folioNum the page's unique identifier
     */
    private void detect(int folioNum, Boolean force) {
        try {
            String imageURL = Folio.getRbTok("SERVERCONTEXT") + getImageURL(folioNum)+"&code="+Folio.getRbTok("imageCode");
            BufferedImage img = imageHelpers.readAsBufferedImage(new URL(imageURL));
            int height = 1000;
            imageProcessor proc = new imageProcessor(img, height);
            
            Vector<detectimages.line> v;
            //There is an aggressive Line detection method that attempts to extract characters from the binarized image
            //and use those pasted on a fresh canvas to detect lines. It is only used for a few specific image hosts
            //and has some error catching for a stack overflow that has occured before due to a recursive call within
            if ( this.getArchive().compareTo("CEEC") == 0 || this.getArchive().compareTo("ecodices")==0 || new Manuscript(this.folioNumber).getCity().compareTo("Baltimore")==0)  {
                try {
                    v = proc.detectLines(true);
                } //if the agressive method fails, log the error and run regular
                catch (Exception e) {
                    Logger.getLogger(Folio.class.getName()).log(Level.SEVERE, "failed using agressive parsing, see error below\n");
                    Logger.getLogger(Folio.class.getName()).log(Level.SEVERE, null, e);
                    v = proc.detectLines(false);
                }
            } else {
                v = proc.detectLines(false);
            }
            detectimages.line[] toret = new detectimages.line[v.size()];
            for (int i = 0; i < toret.length; i++) {
                toret[i] = v.get(i);
            }
            
            //update the Folio with the new Line positions
            new Folio(toret, folioNum);
            
        } catch (Exception ex) {
            Logger.getLogger(Folio.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    
    /**@deprecated old unused constructor
     * 
     * @param folioNumber
     * @param lineCount 
     */
    public Folio(int folioNumber, int lineCount) {
        linePositions = new Line[lineCount];
        this.folioNumber = folioNumber;
    }
    
    
    /**
     * @depricated should be done via the Manuscript object
     * Determine whether any transcriptions exist for the Manuscript this Folio belongs to.
     * @return the number of lines of Transcription that exist
     * @throws SQLException 
     */
    protected int transcriptionExists() throws SQLException {
        Connection j = null;
        PreparedStatement ps=null;
        PreparedStatement stmt=null;
        try {
            int pagecount = 0;
            String folioQuery = "select pageNumber from folios where collection=?";
            j = DatabaseWrapper.getConnection();
            ps = j.prepareStatement(folioQuery);
            ps.setString(1, this.getCollectionName());
            ResultSet folios = ps.executeQuery();
            String list = "0";
            while (folios.next()) {
                list += "," + folios.getString(1);
            }
            String query = "select count(distinct(folio)) from transcription where folio in (" + list + ")";
            stmt = j.prepareStatement(query);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                int count = rs.getInt(1);
                return count;
            }
            return pagecount;
        } finally {
            DatabaseWrapper.closeDBConnection(j);
            DatabaseWrapper.closePreparedStatement(ps);
            DatabaseWrapper.closePreparedStatement(stmt);
        }
    }
    
    
    /**
     * @deprecated should be done via the Manuscript object, and this version is from before connection pooling was working.
     * Determine whether any transcriptions exist for the Manuscript this Folio belongs to, speeding things up by avoiding a connection open .
     * @param j open db connection
     * @return
     * @throws SQLException 
     */
    protected int transcriptionExists(Connection j) throws SQLException {
        PreparedStatement ps=null;
        PreparedStatement stmt=null;
        try {
            int pagecount = 0;
            String folioQuery = "select pageNumber from folios where collection=?";
            //j = DatabaseWrapper.getConnection();
            ps = j.prepareStatement(folioQuery);
            ps.setString(1, this.getCollectionName());
            ResultSet folios = ps.executeQuery();
            String list = "0";
            while (folios.next()) {
                list += "," + folios.getString(1);
            }
            String query = "select count(distinct(folio)) from transcription where folio in (" + list + ")";
            stmt = j.prepareStatement(query);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                int count = rs.getInt(1);
                return count;
            }
            return pagecount;
        }
        finally {
            DatabaseWrapper.closePreparedStatement(ps);
            DatabaseWrapper.closePreparedStatement(stmt);
        }
    }
    
    
    /**
     * build html with the columns as highlighted divisions
     * @return a div containing the lines
     */
    public String getColumnsAsDivs() {
        Line[] cols = getColumns();
        String toret = "";
        String onClick = "onclick=\"deleteme(this,event);\"";
        for (int i = 0; i < cols.length; i++) {
            toret += "<div class=\"line\" style=\"position:absolute;top:";
            toret += cols[i].top;
            toret += "px;left:" + cols[i].left;
            if (cols[i].getWidth() > 0) {
                toret += "px;width:" + cols[i].getWidth();
            } else {
                toret += "px;width:200";
            }
            toret += "px;height:" + (cols[i].bottom);
            toret += "px;background-color:aqua;\" " + onClick + " >";
            toret += "</div>\n";
        }
        return toret;
    }
    
    
    /**
     * Create columns from the lines by grouping them according to left and width. Each column has the same left and width as the lines that constitute it
     * with a top = the top of the first Line and a height = the bottom of the last Line - the top of the top Line
     * @return Line objects representing the columns in the stored image lines
     */
    public Line[] getColumns() {
        Stack<Line> toret = new Stack();
        Hashtable<Integer, Line> h = new Hashtable();
        for (int i = 0; i < linePositions.length; i++) {
            h.put(linePositions[i].left, linePositions[i]);
        }
        Enumeration e = h.elements();
        while (e.hasMoreElements()) {
            //we know the left and width of the column, now find the top and bottom
            Line thisLine = (Line) e.nextElement();
            int top = 99999;
            int bottom = 0;
            for (int i = 0; i < linePositions.length; i++) {
                if (linePositions[i].left == thisLine.left) {
                    if (linePositions[i].top < top) {
                        top = linePositions[i].top;
                    }
                    if (linePositions[i].top > bottom) {
                        bottom = linePositions[i].top;
                    }
                }
            }
            toret.add(new Line(thisLine.left, thisLine.right, top, bottom));
        }
        Line[] linesToRet = new Line[toret.size()];
        for (int i = 0; i < linesToRet.length; i++) {
            linesToRet[i] = toret.get(i);
        }
        return linesToRet;
    }
    
    
    /**
     * @depricated this is from the time before columns were supported...no longer used
     * Save the updated Line positions after sorting them and such
     * @param linePositions
     * @param width 
     */
    public void update(int[] linePositions, int width) {
        Arrays.sort(linePositions);
        Line[] newLines = new Line[linePositions.length];
        for (int i = 0; i < linePositions.length; i++) {
            Line tmp = new Line(0, 0, 0, 0);
            tmp.top = linePositions[i];
            if (i < linePositions.length - 1) {
                tmp.bottom = linePositions[i + 1];
                tmp.left = 0;
                tmp.right = width;
            }
            newLines[i] = tmp;
        }
        this.linePositions = newLines;
        try {
            commit();
        } catch (SQLException ex) {
            Logger.getLogger(Folio.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    
    /**
     * @depricated use update(Line [] lines) instead
     * Insert lines bounded by the given coordinates. All arrays are expected to be the same length
     * @param linePositions tops
     * @param linePositions2 bottoms
     * @param linePositions3 lefts
     * @param linePositions4 rights
     */
    public void update(int[] linePositions, int[] linePositions2, int[] linePositions3, int[] linePositions4) {
        // Arrays.sort(linePositions);
        Line[] newLines = new Line[linePositions.length];
        for (int i = 0; i < linePositions.length; i++) {
            Line tmp = new Line(0, 0, 0, 0);
            tmp.top = linePositions[i];
            tmp.bottom = linePositions4[i];
            tmp.left = linePositions2[i];
            tmp.right = linePositions3[i];
            newLines[i] = tmp;
        }
        this.linePositions = newLines;
        try {
            commit();
        } catch (SQLException ex) {
            Logger.getLogger(Folio.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    
    /**
     * Update the lines for this Folio. Overwrites existing lines
     * @param lines the new lines
     */
    public void update(Line[] lines) {
        this.linePositions = lines;
        try {
            commit();
        } catch (SQLException ex) {
            Logger.getLogger(Folio.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    
    /**
     * Save the state of this object, by deleting the old and inserting the new.
     * @throws SQLException 
     */
    public void commit() throws SQLException {
        Connection j = null;
        PreparedStatement stmt=null;
        try {
            j = DatabaseWrapper.getConnection();
            stmt = j.prepareStatement("Delete from imagepositions where folio=?");
            stmt.setInt(1, folioNumber);
            stmt.execute();
            stmt = j.prepareStatement("Insert into imagepositions (folio,line,top,bottom,colstart,width) values (?,?,?,?,?,?)");
            for (int i = 0; i < linePositions.length; i++) {
                stmt.setInt(1, folioNumber);
                stmt.setInt(2, i + 1);
                stmt.setInt(3, linePositions[i].top);
                stmt.setInt(4, linePositions[i].bottom);
                stmt.setInt(5, linePositions[i].left);
                stmt.setInt(6, linePositions[i].right);
                stmt.execute();
            }
        } finally {
            DatabaseWrapper.closeDBConnection(j);
            DatabaseWrapper.closePreparedStatement(stmt);
        }
    }
    
    
    /** 
     * Return the Line parsing associated with this page. These are the publicly available, stored ones or the newly parsed ones
     * not ones specific to a project
     * @return the lines
     */
    public Line[] getlines() {
        return this.linePositions;
    }
    
    
    /**
     * Calculate the mean Line height for this page. This is useful for padding lines.
     * @return rounded mean height or 25 if there was a problem (25 has been a good default for this historically) 
     */
    public int getMeanHeight() {
        int mean = 0;
        for (int i = 0; i < linePositions.length; i++) {
            if (linePositions[i] != null) {
                if (linePositions[i].bottom > linePositions[i].top) {
                    mean += linePositions[i].bottom - linePositions[i].top;
                }
            }
        }
        if (linePositions.length == 0) {
            return 25;
        }
        if (!zoom) {
            return mean / linePositions.length;
        } else {
            return (mean / linePositions.length) * 2;
        }
    }
    
    
    /**@depricated you are probably trying to render an image of the Line of the ms with a Line of Transcription, so use the Transcription getx, gety etc to draw this
     * 
     * Return a div containing the are of the page image associated with the requested Line
     *@param lineNum the number of the Line you want.
     */
    public String getLineAsDiv(int lineNum) {
        int mean = getMeanHeight();
        String toret = "<div style=\"position:relative;height:" + (mean * .5 + ((linePositions[lineNum - 1].getBottom() - linePositions[lineNum - 1].getTop()))) + "px;overflow:hidden;\">";
        try {
            if (this.archive != null && this.archive.length() > 0) {
                toret += "<img style=\"position:relative;top:-" + linePositions[lineNum - 1].getTop() + "px;\" src=\"" + this.getImageURLResize(1000) + "\"/>";
            }
            
        } catch (SQLException ex) {
            Logger.getLogger(Folio.class.getName()).log(Level.SEVERE, null, ex);
        }
        toret += "</div>";
        return toret;
    }
    
    
    /**
     * @deprecated use Folio.getIPRAgreement() instead
     * Retrieve the copyright notice used by this Archive, tailored to include the collection and page info.
     * @param Archive The internal Archive name
     * @param collection 
     * @param page
     * @return The copyright notice or a dummy one if nothing has been setup
     */
    public String getCopyrightNotice(String archive, String collection, String page) {
        String toret = "";
        try {
            toret += textdisplay.Archive.getCopyrightNotice(archive, collection, page);
        } catch (SQLException ex) {
            Logger.getLogger(Folio.class.getName()).log(Level.SEVERE, null, ex);
        }
        return toret;
    }
    
    
    /**
     * Retrieve the name of the Archive that houses this MS page
     * @return Archive name for internal use by TPEN to describe the image host that houses the Manuscript images, not the official name of the Repository the MSS belongs to
     */
    public String getArchive() {
        String toret = "";
        if (archive != null) {
            toret = archive;
        }
        return toret;
    }
    
    
    /**Create a proper shelfmark for this ms page*/
    public String getArchiveShelfMark() {
        return textdisplay.Archive.getShelfMark(archive);
    }
    
    
    /**
     * Build OAC rdf Transcription annotations from this page.
     * @param uid user id of the requestor
     * @return N3 format serialized rdf
     * @throws SQLException 
     */
    public String getOAC(int uid) throws SQLException {
        String toret = "";
        Manuscript ms = new Manuscript(this.folioNumber);
        Model model = ModelFactory.createDefaultModel();
        model.setNsPrefix("dms", "http://dms.stanford.edu/ns/");
        model.setNsPrefix("oac", "http://www.openannotation.org/ns/");
        model.setNsPrefix("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
        model.setNsPrefix("ore", "http://www.openarchives.org/ore/terms/");
        model.setNsPrefix("cnt", "http://www.w3.org/2008/content#");
        model.setNsPrefix("dc", "http://purl.org/dc/elements/1.1/");
        model.setNsPrefix("dcterms", "http://purl.org/dc/terms/");
        Transcription[] transcriptions = Transcription.getPersonalTranscriptions(uid, folioNumber);
        Folio f = new Folio(this.folioNumber);
        //Resource image=model.createResource(f.getArchiveLink());
        Resource transc = model.createResource("http://dms.stanford.edu/ns/TranscriptionAnnotation");
        Property transcriptionProperty = model.createProperty("http://dms.stanford.edu/ns/", "TranscriptionAnnotation");
        Property oacTarget = model.createProperty("http://www.openannotation.org/ns/", "hasTarget");
        Property oacBody = model.createProperty("http://www.openannotation.org/ns/", "hasBody");
        Property contentChars = model.createProperty("http://www.w3.org/2008/content#", "chars");
        Property rdfType = model.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "type");
        Resource viewFrag = model.createResource("http://www.openannotation.org/ns/Annotation");
        Property isPartOf = model.createProperty("http://purl.org/dc/terms/", "isPartOf");
        Resource fullImage = model.createResource("http://t-pen.org/views/" + this.folioNumber);
        
        String[] uuids = new String[transcriptions.length];
        Resource thisPage = model.createResource("http://t-pen.org/transcription/" + this.folioNumber);
        Resource[] items = new Resource[uuids.length];
        Property aggregates = model.createProperty("http://www.openarchives.org/ore/terms/", "aggregates");
        for (int i = 0; i < transcriptions.length; i++) {
            uuids[i] = java.util.UUID.randomUUID().toString();
            Resource item = model.createResource("urn:uuid:" + uuids[i]);
            items[i] = item;
            //  StringWriter tmp=new StringWriter();
            //  model.write(tmp);
            // toret+=uuids[i]+" should be last\n"+tmp.toString()+"\n";
        }
        toret += "list:\n";
        RDFList l = model.createList(items);
        
        for (int i = 0; i < transcriptions.length; i++) {
            String uuid = uuids[i];
            Resource item = model.createResource("urn:uuid:" + uuid);
            /**
             * @TODO change to use Transcription.getProjectTranscriptions
             */
            Resource thisLine = model.createResource("http://t-pen.org/transcription/" + this.folioNumber + "/" + i);
            String xyhw = "#xywh=" + transcriptions[i].getX() + ", " + transcriptions[i].getY() + ", " + transcriptions[i].getHeight() + ", " + transcriptions[i].getWidth();
            Resource image = model.createResource(f.getArchiveLink());//+"#xyhw="+xyhw);
            Literal text = model.createLiteral(transcriptions[i].getText());
            item.addProperty(oacBody, thisLine);
            item.addProperty(oacTarget, image + xyhw);
            item.addProperty(rdfType, transc);
            image.addProperty(rdfType, viewFrag);
            image.addProperty(oacTarget, fullImage);
            thisLine.addProperty(contentChars, text);
        }
        StringWriter tmp = new StringWriter();
        model.write(tmp, "N3");
        toret += tmp.getBuffer().toString();
        return toret;
    }
    
    
    /**Create a link to this MS page at the host Archive, not currently implemented*/
    public String getArchiveLink() {
        try {
            return textdisplay.Archive.getURL(folioNumber);
        } catch (SQLException ex) {
            Logger.getLogger(Folio.class.getName()).log(Level.SEVERE, null, ex);
        }
        // @TODO:  Move this to some kind of configuration variable.
        return "http://t-pen.org";
    }
    
    
    /**Reset the detected lines in the image to whatever the Line detector finds. Useful when the detector improves or someone
    puts bad parsings in the public set*/
    public void reset() throws SQLException {
        Connection j = null;
        PreparedStatement stmt=null;
        try {
            j = DatabaseWrapper.getConnection();
            stmt = j.prepareStatement("Delete from imagepositions where folio=?");
            stmt.setInt(1, folioNumber);
            stmt.execute();
            
            this.detect(folioNumber, false);
        } finally {
            DatabaseWrapper.closeDBConnection(j);
            DatabaseWrapper.closePreparedStatement(stmt);
        }
    }
    
    
    /**
     * @deprecated use Manuscript.getNextFolio instead
     * Return the Folio number of the next page, a value of -1 indicates there is no following page
     * @return
     * @throws SQLException 
     */
    public int getNextFolio() throws SQLException {
        Manuscript ms = new Manuscript(this.folioNumber);
        int[] folioNums = ms.getFolioNumbers();
        for (int i = 0; i < folioNums.length; i++) {
            if (folioNums[i] == this.folioNumber) {
                if (i < folioNums.length - 1) {
                    return folioNums[i + 1];
                }
            }
        }
        return -1;
    }
    
    
    /**
     * @depricated use Manuscript.
     * Return the Folio number of the previous page, a value of -1 indicates there is no previous page
     * @return Folio number of the previous page
     * @throws SQLException
     */
    public int getPrevFolio() throws SQLException {
        Manuscript ms = new Manuscript(this.folioNumber);
        int[] folioNums = ms.getFolioNumbers();
        for (int i = 0; i < folioNums.length; i++) {
            if (folioNums[i] == this.folioNumber) {
                if (i > 0) {
                    return folioNums[i - 1];
                }
            }
            
        }
        return -1;
        
    }
    
    
    /**
     * Retrieve the Archive specific IPR agreement for this image.
     * @return Text of the Archive's IPR agreement
     */
    public String getIPRAgreement() {
        try {
            Archive a = new Archive(this.archive);
            return a.getIPRAgreement();
            /**/
        } catch (SQLException ex) {
            Logger.getLogger(Folio.class.getName()).log(Level.SEVERE, null, ex);
        }
        String toret = "An error occured fetching the IPR agreement.";
        return toret;
    }
}
