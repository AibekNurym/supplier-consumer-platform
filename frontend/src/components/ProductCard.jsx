import React from 'react'
import { EditIcon, Trash2Icon } from "lucide-react";
import { Link } from "react-router-dom";
import { useProductStore } from "../store/useProductStore";
import { useAuth } from "../contexts/AuthContext";
import { useTranslation } from 'react-i18next';

function ProductCard({ product }) {
    const { deleteProduct } = useProductStore();
    const { api, hasPermission } = useAuth();
    const { t } = useTranslation();
    
    return (
        <div className='card bg-base-100 shadow-xl hover:shadow-2xl transition-shadow duration-300'>
            {/* PRODUCT IMAGE */}
            <figure className="relative pt-[56.25%]">
                <img
                    src={product.image}
                    alt={product.name}
                    className="absolute top-0 left-0 w-full h-full object-cover"
                />
            </figure>
            <div className="card-body">
                {/* PRODUCT INFO */}
                <h2 className="card-title text-lg font-semibold">{product.name}</h2>
                {product.discount_percentage && parseFloat(product.discount_percentage) > 0 ? (
                    <div className="space-y-1">
                        <p className="text-lg line-through text-gray-400">₸{Number(product.price).toFixed(2)}</p>
                        <p className="text-2xl font-bold text-primary">
                            ₸{((Number(product.price) || 0) * (1 - (parseFloat(product.discount_percentage) || 0) / 100)).toFixed(2)}
                        </p>
                        <span className="badge badge-success">{t('{{percent}}% off', { percent: product.discount_percentage })}</span>
                    </div>
                ) : (
                    <p className="text-2xl font-bold text-primary">₸{Number(product.price).toFixed(2)}</p>
                )}
                <p className="text-sm text-secondary">
                    <span className="font-medium">{t('Available:')}</span> {product.available_quantity}
                </p>

                <p className="text-sm text-secondary">
                    <span className="font-medium">{t('Minimum Order Quantity:')}</span> {product.minimum_order_quantity}
                </p>

                {product.lead_time_days && product.lead_time_days > 0 && (
                    <p className="text-sm text-info">
                        <span className="font-medium">📅 {t('Lead Time:')}</span> {t('Lead Time day', { count: product.lead_time_days })}
                    </p>
                )}

                <p className="text-sm text-secondary line-clamp-3">
                    {product.description}
                </p>

                {/* CARD ACTIONS */}
                <div className="card-actions justify-end mt-4">
                    {hasPermission('products.update') && (
                        <Link 
                            to={`/product/${product.id}`} 
                            className="btn btn-sm btn-info btn-outline"
                            aria-label={t('Edit {{name}}', { name: product.name })}
                        >
                            <EditIcon className="size-4" aria-hidden="true" />
                        </Link>
                    )}

                    {hasPermission('products.delete') && (
                        <button
                            className="btn btn-sm btn-error btn-outline"
                            onClick={() => deleteProduct(product.id, api)}
                            aria-label={t('Delete {{name}}', { name: product.name })}
                        >
                            <Trash2Icon className="size-4" aria-hidden="true" />
                        </button>
                    )}
                </div>
            </div>
        </div>
    );
}
export default ProductCard;