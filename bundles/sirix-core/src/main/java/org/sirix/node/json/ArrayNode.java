/**
 * Copyright (c) 2018, Sirix
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of the <organization> nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.sirix.node.json;

import java.math.BigInteger;
import org.sirix.api.visitor.JsonNodeVisitor;
import org.sirix.api.visitor.VisitResult;
import org.sirix.node.NodeKind;
import org.sirix.node.delegates.NodeDelegate;
import org.sirix.node.delegates.StructNodeDelegate;
import org.sirix.node.immutable.json.ImmutableArrayNode;
import org.sirix.node.interfaces.Node;
import org.sirix.node.interfaces.immutable.ImmutableJsonNode;
import org.sirix.node.xdm.AbstractStructForwardingNode;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

/**
 * @author Johannes Lichtenberger <lichtenberger.johannes@gmail.com>
 */
public final class ArrayNode extends AbstractStructForwardingNode implements ImmutableJsonNode {

  /** {@link StructNodeDelegate} reference. */
  private final StructNodeDelegate mStructNodeDel;

  /** The path node key. */
  private final long mPathNodeKey;

  private BigInteger mHash;

  /**
   * Constructor
   *
   * @param structDel {@link StructNodeDelegate} to be set
   * @param pathNodeKey the path node key
   */
  public ArrayNode(final StructNodeDelegate structDel, final long pathNodeKey) {
    assert structDel != null;
    mStructNodeDel = structDel;
    mPathNodeKey = pathNodeKey;
  }

  /**
   * Constructor
   *
   * @param structDel {@link StructNodeDelegate} to be set
   * @param pathNodeKey the path node key
   */
  public ArrayNode(final BigInteger hashCode, final StructNodeDelegate structDel, final long pathNodeKey) {
    assert hashCode != null;
    mHash = hashCode;
    assert structDel != null;
    mStructNodeDel = structDel;
    mPathNodeKey = pathNodeKey;
  }

  @Override
  public NodeKind getKind() {
    return NodeKind.ARRAY;
  }

  @Override
  public BigInteger computeHash() {
    BigInteger result = BigInteger.ONE;

    result = BigInteger.valueOf(31).multiply(result).add(mStructNodeDel.getNodeDelegate().computeHash());
    result = BigInteger.valueOf(31).multiply(result).add(mStructNodeDel.computeHash());

    return Node.to128BitsAtMaximumBigInteger(result);
  }

  @Override
  public void setHash(final BigInteger hash) {
    mHash = Node.to128BitsAtMaximumBigInteger(hash);
  }

  @Override
  public BigInteger getHash() {
    return mHash;
  }

  @Override
  protected NodeDelegate delegate() {
    return mStructNodeDel.getNodeDelegate();
  }

  @Override
  protected StructNodeDelegate structDelegate() {
    return mStructNodeDel;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("structDelegate", mStructNodeDel).toString();
  }

  @Override
  public int hashCode() {
    return delegate().hashCode();
  }

  @Override
  public boolean equals(final Object obj) {
    if (!(obj instanceof ObjectKeyNode))
      return false;

    final ObjectKeyNode other = (ObjectKeyNode) obj;
    return Objects.equal(delegate(), other.delegate());
  }

  @Override
  public VisitResult acceptVisitor(final JsonNodeVisitor visitor) {
    return visitor.visit(ImmutableArrayNode.of(this));
  }

  public long getPathNodeKey() {
    return mPathNodeKey;
  }
}
