<?xml version="1.0" encoding="UTF-8"?>
<!--
 * Copyright (c) 2021, Oracle and/or its affiliates.
 * Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.
-->
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform" xmlns:xsi="test">
	<xsl:template match="node()">
		<xsl:copy>
			<xsl:apply-templates select="node() | @*" />
		</xsl:copy>
	</xsl:template>
	<xsl:template match="*">
		<xsl:element name="{local-name()}">
			<xsl:apply-templates select="node() | @*" />
		</xsl:element>
	</xsl:template>
	<xsl:template match="@*">
		<xsl:if test="xsi:nil='true'">
			<xsl:copy>
				<xsl:apply-templates select="node() | @*" />
			</xsl:copy>
		</xsl:if>
	</xsl:template>

</xsl:stylesheet>