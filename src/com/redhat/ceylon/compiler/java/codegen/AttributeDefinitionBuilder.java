/*
 * Copyright Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the authors tag. All rights reserved.
 *
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU General Public License version 2.
 * 
 * This particular file is subject to the "Classpath" exception as provided in the 
 * LICENSE file that accompanied this code.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License,
 * along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 */

package com.redhat.ceylon.compiler.java.codegen;

import com.redhat.ceylon.compiler.java.util.Decl;
import com.redhat.ceylon.compiler.java.util.Util;
import com.redhat.ceylon.compiler.typechecker.model.ProducedType;
import com.redhat.ceylon.compiler.typechecker.model.TypedDeclaration;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;

/**
 * Builds a class for global variables. See {@link GlobalTransformer} for an overview.
 *
 * The generated class can be customized by calling methods of this class.
 */
public class AttributeDefinitionBuilder {
    private boolean hasField = true;
    private final String fieldName;

    private final JCTree.JCExpression attrType;
    private final JCTree.JCExpression attrTypeRaw;
    private final String attrName;
    private String className;

    private long modifiers;

    private final ListBuffer<JCTree.JCAnnotation> annotations = ListBuffer.lb();

    private boolean readable = true;
    private final MethodDefinitionBuilder getterBuilder;

    private JCTree.JCExpression variableInit;

    private boolean writable = true;
    private final MethodDefinitionBuilder setterBuilder;
    
    private AbstractTransformer owner;
    private boolean ancestorLocal;

    private boolean toplevel = false;

    private AttributeDefinitionBuilder(AbstractTransformer owner, TypedDeclaration attrType, String className, String attrName, String fieldName, boolean toplevel) {
        int typeFlags = 0;
        TypedDeclaration nonWideningTypeDeclaration = owner.nonWideningTypeDecl(attrType);
        ProducedType nonWideningType = owner.nonWideningType(attrType, nonWideningTypeDeclaration);
        if (!Util.isUnBoxed(nonWideningTypeDeclaration)) {
            typeFlags |= AbstractTransformer.NO_PRIMITIVES;
        }
        // Special erasure for the "hash" attribute which gets translated to hashCode()
        if (Util.isSmall(attrType)) {
            typeFlags = AbstractTransformer.SMALL_TYPE;
        }
        
        this.ancestorLocal = Decl.isAncestorLocal(attrType);
        this.attrType = owner.makeJavaType(nonWideningType, typeFlags);
        this.attrTypeRaw = owner.makeJavaType(nonWideningType, AbstractTransformer.WANT_RAW_TYPE);
        this.owner = owner;
        this.className = className;
        this.attrName = attrName;
        this.fieldName = fieldName;
        this.toplevel = toplevel;
        
        // Make sure we use the declaration for building the getter/setter names, as we might be trying to
        // override a JavaBean property with an "isFoo" getter, or non-Ceylon casing, and we have to respect that.
        getterBuilder = MethodDefinitionBuilder
            .systemMethod(owner, ancestorLocal, Util.getGetterName(attrType))
            .block(generateDefaultGetterBlock())
            .isActual(attrType.isActual())
            .annotations(owner.makeAtAnnotations(attrType.getAnnotations()))
            .resultType(this.attrType, attrType);
        setterBuilder = MethodDefinitionBuilder
            .systemMethod(owner, ancestorLocal, Util.getSetterName(attrType))
            .block(generateDefaultSetterBlock())
            // only actual if the superclass is also variable
            .isActual(attrType.isActual() && ((TypedDeclaration)attrType.getRefinedDeclaration()).isVariable())
            .parameter(0, attrName, attrType, nonWideningTypeDeclaration, nonWideningType);
    }

    public static AttributeDefinitionBuilder wrapped(AbstractTransformer owner, String name, TypedDeclaration attrType, boolean toplevel) {
        return new AttributeDefinitionBuilder(owner, attrType, name, name, "value", toplevel);
    }
    
    public static AttributeDefinitionBuilder getter(AbstractTransformer owner, String name, TypedDeclaration attrType) {
        return new AttributeDefinitionBuilder(owner, attrType, null, name, name, false)
            .skipField()
            .immutable();
    }
    
    public static AttributeDefinitionBuilder setter(AbstractTransformer owner, String name, TypedDeclaration attrType) {
        return new AttributeDefinitionBuilder(owner, attrType, null, name, name, false)
            .skipField()
            .skipGetter();
    }
    
    /**
     * Generates the class and returns the generated tree.
     * @return the generated class tree, to be added to the appropriate {@link JCTree.JCCompilationUnit}.
     */
    public List<JCTree> build() {
        ListBuffer<JCTree> defs = ListBuffer.lb();
        appendDefinitionsTo(defs);
        if (className != null) {
            return ClassDefinitionBuilder
                .klass(owner, ancestorLocal, className)
                .modifiers(Flags.FINAL | (modifiers & (Flags.PUBLIC | Flags.PRIVATE)))
                .constructorModifiers(Flags.PRIVATE)
                .annotations(!ancestorLocal ? owner.makeAtAttribute() : List.<JCTree.JCAnnotation>nil())
                .annotations(annotations.toList())
                .defs(defs.toList())
                .build();
        } else {
            return defs.toList();
        }
    }

    /**
     * Appends to <tt>defs</tt> the definitions that would go into the class generated by {@link #build()}
     * @param defs a {@link ListBuffer} to which the definitions will be appended.
     */
    public void appendDefinitionsTo(ListBuffer<JCTree> defs) {
        if (hasField) {
            defs.append(generateField());
            if(variableInit != null)
                defs.append(generateFieldInit());
        }

        if (readable) {
            getterBuilder.modifiers(getGetSetModifiers());
            defs.append(getterBuilder.build());
        }

        if (writable) {
            setterBuilder.modifiers(getGetSetModifiers());
            defs.append(setterBuilder.build());
        }
    }

    private long getGetSetModifiers() {
        return modifiers & (Flags.PUBLIC | Flags.PRIVATE | Flags.ABSTRACT | Flags.FINAL | Flags.STATIC);
    }

    private JCTree generateField() {
        long flags = Flags.PRIVATE | (modifiers & Flags.STATIC);
        // only make it final if we have an init, otherwise we still have to initialise it
        if (!writable && variableInit != null) {
            flags |= Flags.FINAL;
        }

        return owner.make().VarDef(
                owner.make().Modifiers(flags),
                owner.names().fromString(Util.quoteIfJavaKeyword(fieldName)),
                (toplevel) ? owner.make().TypeArray(attrType) : attrType,
                null
        );
    }

    private JCTree generateFieldInit() {
        long flags = (modifiers & Flags.STATIC);

        JCTree.JCExpression varInit = variableInit;
        if (toplevel) {
            varInit = owner.make().NewArray(
                    attrTypeRaw,
                    List.<JCTree.JCExpression>nil(),
                    List.<JCTree.JCExpression>of(varInit)
            );
        }
        JCTree.JCAssign init = owner.make().Assign(owner.makeUnquotedIdent(fieldName), varInit);
        return owner.make().Block(flags, 
                List.<JCTree.JCStatement>of(owner.make().Exec(init)));
    }

    private JCTree.JCBlock generateDefaultGetterBlock() {
        JCTree.JCExpression returnExpr = owner.makeUnquotedIdent(fieldName);
        if (toplevel) {
            returnExpr = owner.make().Indexed(returnExpr, owner.make().Literal(0));
        }
        JCTree.JCBlock block = owner.make().Block(0L, List.<JCTree.JCStatement>of(owner.make().Return(returnExpr)));
        if (toplevel) {
            JCTree.JCThrow throwStmt = owner.make().Throw(owner.makeNewClass(owner.make().Type(owner.syms().ceylonRecursiveInitializationExceptionType), null));
            JCTree.JCBlock catchBlock = owner.make().Block(0, List.<JCTree.JCStatement>of(throwStmt));
            JCVariableDecl excepType = owner.makeVar("ex", owner.make().Type(owner.syms().nullPointerExceptionType), null);
            JCTree.JCCatch catcher = owner.make().Catch(excepType , catchBlock);
            JCTree.JCTry tryExpr = owner.make().Try(block, List.<JCTree.JCCatch>of(catcher), null);
            block = owner.make().Block(0L, List.<JCTree.JCStatement>of(tryExpr));
        }
        return block;
    }

    public JCTree.JCBlock generateDefaultSetterBlock() {
        JCTree.JCExpression fld;
        if (fieldName.equals(attrName)) {
            fld = owner.makeSelect("this", fieldName);
        } else {
            fld = owner.makeUnquotedIdent(fieldName);
        }
        if (toplevel) {
            fld = owner.make().Indexed(fld, owner.make().Literal(0));
        }
        return owner.make().Block(0L, List.<JCTree.JCStatement>of(
                owner.make().Exec(
                        owner.make().Assign(
                                fld,
                                owner.makeUnquotedIdent(attrName)))));
    }

    /**
     * Sets the name for generated class.
     * If not used will use the same name as for the variable.
     * @param className the new class name
     * @return this instance for method chaining
     */
    public AttributeDefinitionBuilder className(String className) {
        this.className = className;
        return this;
    }
    
    public AttributeDefinitionBuilder modifiers(long... modifiers) {
        long mods = 0;
        for (long mod : modifiers) {
            mods |= mod;
        }
        this.modifiers = mods;
        return this;
    }

    public AttributeDefinitionBuilder is(long flag, boolean value) {
        if (value) {
            this.modifiers |= flag;
        } else {
            this.modifiers &= ~flag;
        }
        return this;
    }

    public AttributeDefinitionBuilder annotations(List<JCTree.JCAnnotation> annotations) {
        if (ancestorLocal) {
            return this;
        }
        this.annotations.appendList(annotations);
        return this;
    }

    public AttributeDefinitionBuilder isFormal(boolean isFormal) {
        getterBuilder.isFormal(isFormal);
        setterBuilder.isFormal(isFormal);
        return this;
    }

    /**
     * Causes no field to be generated.
     * @return this instance for method chaining
     */
    public AttributeDefinitionBuilder skipField() {
        this.hasField = false;
        return this;
    }

    /**
     * Sets the code block to use for the generated getter. If no getter is generated the code block will be
     * silently ignored.
     * @param getterBlock a code block
     * @return this instance for method chaining
     */
    public AttributeDefinitionBuilder getterBlock(JCTree.JCBlock getterBlock) {
        skipField();
        getterBuilder.block(getterBlock);
        return this;
    }

    /**
     * Causes no getter to be generated.
     * @return this instance for method chaining
     */
    public AttributeDefinitionBuilder skipGetter() {
        this.readable = false;
        return this;
    }

    /**
     * Sets the code block to use for the generated setter. If no setter is generated the code block will be
     * silently ignored.
     * @param setterBlock a code block
     * @return this instance for method chaining
     */
    public AttributeDefinitionBuilder setterBlock(JCTree.JCBlock setterBlock) {
        setterBuilder.block(setterBlock);
        return this;
    }

    /**
     * Causes the generated attribute to be immutable. The <tt>value</tt> field is declared <tt>final</tt> and no
     * setter is generated.
     * @return this instance for method chaining
     */
    public AttributeDefinitionBuilder immutable() {
        this.writable = false;
        return this;
    }

    /**
     * The <tt>value</tt> field will be declared with the initial value given by the parameter
     * @param initialValue the initial value of the global.
     * @return this instance for method chaining
     */
    public AttributeDefinitionBuilder initialValue(JCTree.JCExpression initialValue) {
        this.variableInit = initialValue;
        return this;
    }

    /**
     * Marks the getter/setter methods as not actual. In general <tt>actual</tt> is derived from
     * the model while creating this builder so it will be correct. You can only disable this
     * computation. Enabling <tt>actual</tt> would otherwise depend on the question of whether the
     * getter is or not actual which may be different for the setter if the refined decl is not variable
     * so we'd need two parameters.
     */
    public AttributeDefinitionBuilder notActual() {
        getterBuilder.isActual(false);
        setterBuilder.isActual(false);
        return this;
    }
}